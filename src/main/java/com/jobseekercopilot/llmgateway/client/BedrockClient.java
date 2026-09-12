package com.jobseekercopilot.llmgateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.llmgateway.config.BedrockConfiguration;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationRefusedException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import com.jobseekercopilot.llmgateway.resilience.ProviderCircuitBreaker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.SdkNumber;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.BedrockRuntimeException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * AWS Bedrock provider client. Uses the Bedrock Converse API, which offers a
 * strongly typed, model-agnostic request/response format. It is wired only when
 * {@code external-provider.mode=BEDROCK} and satisfies the same
 * {@link LlmProviderClient} contract as {@link OpenAiClient}, so the gateway
 * service, resilience and billing plumbing are unchanged.
 *
 * <p>For {@link GenerationOutputFormat#JSON_SCHEMA} requests the client uses the
 * Converse tool-use mechanism: a single tool is declared with the requested JSON
 * Schema as its input schema and the model is forced to call it, which yields
 * structured JSON output. Plain {@link GenerationOutputFormat#TEXT} requests use
 * ordinary message content.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "BEDROCK")
public class BedrockClient implements LlmProviderClient {

    static final String ADAPTER_ID = "bedrock";
    private static final String STRUCTURED_OUTPUT_TOOL = "emit_structured_output";

    private final BedrockConfiguration configuration;
    private final BedrockRuntimeClient runtimeClient;
    private final ObjectMapper objectMapper;
    private final ProviderCircuitBreaker circuitBreaker;

    @Autowired
    public BedrockClient(
            BedrockConfiguration configuration,
            BedrockRuntimeClient runtimeClient,
            ProviderCircuitBreaker circuitBreaker
    ) {
        this(configuration, runtimeClient, new ObjectMapper(), circuitBreaker);
    }

    BedrockClient(
            BedrockConfiguration configuration,
            BedrockRuntimeClient runtimeClient,
            ObjectMapper objectMapper,
            ProviderCircuitBreaker circuitBreaker
    ) {
        this.configuration = configuration;
        this.runtimeClient = runtimeClient;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ProviderGenerationResult generate(GenerationCommand command) {
        long startedAt = System.nanoTime();
        ConverseRequest request = buildRequest(command);

        log.info("Bedrock provider request started model={} temperature={} maxTokens={} format={}",
                configuration.getModelId(), command.temperature(), command.maxOutputTokens(),
                command.outputFormat());

        ProviderCircuitBreaker.Permit permit;
        try {
            permit = circuitBreaker.acquirePermit();
        } catch (ProviderFailureException exception) {
            log.warn("Bedrock provider call rejected failureType={} attempt=1 automaticRetries=0",
                    exception.getType());
            throw exception;
        }

        try {
            ConverseResponse response = runtimeClient.converse(request);
            ProviderGenerationResult result = mapResponse(command, response);
            circuitBreaker.recordSuccess(permit);
            log.info("Bedrock provider returned stopReason={} durationMs={} requestId={} attempt=1 automaticRetries=0",
                    response.stopReasonAsString(),
                    (System.nanoTime() - startedAt) / 1_000_000,
                    safeRequestId(response));
            return result;
        } catch (GenerationRefusedException exception) {
            circuitBreaker.recordSuccess(permit);
            log.warn("Bedrock provider refused generation durationMs={} attempt=1 automaticRetries=0",
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        } catch (GenerationBoundaryException exception) {
            circuitBreaker.recordFailure(permit, ProviderFailureType.INVALID_RESPONSE);
            log.warn("Bedrock provider response rejected failureType={} durationMs={} attempt=1 automaticRetries=0",
                    ProviderFailureType.INVALID_RESPONSE,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        } catch (BedrockRuntimeException | SdkClientException exception) {
            ProviderFailureException providerFailure = mapFailure(exception);
            circuitBreaker.recordFailure(permit, providerFailure.getType());
            log.warn("Bedrock provider call failed failureType={} durationMs={} attempt=1 automaticRetries=0",
                    providerFailure.getType(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw providerFailure;
        }
    }

    private ConverseRequest buildRequest(GenerationCommand command) {
        Message userMessage = Message.builder()
                .role(ConversationRole.USER)
                .content(ContentBlock.fromText(command.untrustedInput()))
                .build();

        InferenceConfiguration inferenceConfig = InferenceConfiguration.builder()
                .maxTokens(command.maxOutputTokens())
                .temperature((float) command.temperature())
                .build();

        ConverseRequest.Builder builder = ConverseRequest.builder()
                .modelId(configuration.getModelId())
                .messages(userMessage)
                .system(SystemContentBlock.fromText(command.trustedInstructions()))
                .inferenceConfig(inferenceConfig);

        if (command.outputFormat() == GenerationOutputFormat.JSON_SCHEMA) {
            builder.toolConfig(structuredOutputToolConfig(command));
        }

        return builder.build();
    }

    private ToolConfiguration structuredOutputToolConfig(GenerationCommand command) {
        ToolSpecification toolSpecification = ToolSpecification.builder()
                .name(STRUCTURED_OUTPUT_TOOL)
                .description("Return the requested document strictly as the declared JSON schema.")
                .inputSchema(ToolInputSchema.builder()
                        .json(toDocument(command.jsonSchema()))
                        .build())
                .build();

        return ToolConfiguration.builder()
                .tools(Tool.builder().toolSpec(toolSpecification).build())
                .toolChoice(ToolChoice.builder()
                        .tool(builder -> builder.name(STRUCTURED_OUTPUT_TOOL))
                        .build())
                .build();
    }

    private ProviderGenerationResult mapResponse(GenerationCommand command, ConverseResponse response) {
        StopReason stopReason = response.stopReason();
        if (stopReason == StopReason.CONTENT_FILTERED || stopReason == StopReason.GUARDRAIL_INTERVENED) {
            throw new GenerationRefusedException();
        }
        if (stopReason == StopReason.UNKNOWN_TO_SDK_VERSION) {
            throw invalidResponse("The provider returned an unrecognised stop reason.");
        }

        if (response.output() == null || response.output().message() == null) {
            throw invalidResponse("The provider response did not contain a message.");
        }
        List<ContentBlock> content = response.output().message().content();
        if (content == null || content.isEmpty()) {
            throw invalidResponse("The provider message did not contain output.");
        }

        String output = command.outputFormat() == GenerationOutputFormat.JSON_SCHEMA
                ? extractStructuredOutput(content)
                : extractTextOutput(content);
        if (!StringUtils.hasText(output)) {
            throw invalidResponse("The provider message did not contain output.");
        }

        return new ProviderGenerationResult(
                output.trim(),
                mapUsage(response.usage()),
                mapFinishReason(stopReason),
                ADAPTER_ID,
                requiredModelId(response)
        );
    }

    private String extractTextOutput(List<ContentBlock> content) {
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : content) {
            if (block.text() != null) {
                builder.append(block.text());
            }
        }
        return builder.toString();
    }

    private String extractStructuredOutput(List<ContentBlock> content) {
        ToolUseBlock toolUse = content.stream()
                .map(ContentBlock::toolUse)
                .filter(block -> block != null && STRUCTURED_OUTPUT_TOOL.equals(block.name()))
                .findFirst()
                .orElseThrow(() -> invalidResponse(
                        "The provider did not return the requested structured output."));
        try {
            return objectMapper.writeValueAsString(fromDocument(toolUse.input()));
        } catch (JsonProcessingException exception) {
            throw invalidResponse("The provider structured output could not be serialised.");
        }
    }

    private String requiredModelId(ConverseResponse response) {
        // The Converse response does not echo the model id, so the configured
        // model (or inference-profile) identifier is authoritative.
        String modelId = configuration.getModelId();
        if (!StringUtils.hasText(modelId) || modelId.length() > 128) {
            throw invalidResponse("The provider model identifier is not configured.");
        }
        return modelId;
    }

    private GenerationUsage mapUsage(TokenUsage usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            throw invalidResponse("The provider response did not contain token usage.");
        }
        long inputTokens = usage.inputTokens();
        long outputTokens = usage.outputTokens();
        long totalTokens = usage.totalTokens() != null
                ? usage.totalTokens()
                : inputTokens + outputTokens;
        return new GenerationUsage(inputTokens, outputTokens, totalTokens);
    }

    private GenerationFinishReason mapFinishReason(StopReason stopReason) {
        return switch (stopReason) {
            case END_TURN, STOP_SEQUENCE, TOOL_USE -> GenerationFinishReason.COMPLETED;
            case MAX_TOKENS -> GenerationFinishReason.LIMIT_REACHED;
            case CONTENT_FILTERED, GUARDRAIL_INTERVENED -> GenerationFinishReason.FILTERED;
            default -> GenerationFinishReason.UNKNOWN;
        };
    }

    private ProviderFailureException mapFailure(RuntimeException exception) {
        if (exception instanceof AccessDeniedException) {
            return new ProviderFailureException(
                    ProviderFailureType.AUTHENTICATION,
                    "The provider rejected its configured credentials.");
        }
        if (exception instanceof ServiceQuotaExceededException) {
            return new ProviderFailureException(
                    ProviderFailureType.QUOTA_EXHAUSTED,
                    "The provider account quota is exhausted.");
        }
        if (exception instanceof ThrottlingException) {
            return new ProviderFailureException(
                    ProviderFailureType.RATE_LIMITED,
                    "The provider rate limit was exceeded.");
        }
        if (exception instanceof ModelTimeoutException) {
            return new ProviderFailureException(
                    ProviderFailureType.TIMEOUT,
                    "The provider timed out.");
        }
        if (exception instanceof ServiceUnavailableException || exception instanceof ModelErrorException) {
            return new ProviderFailureException(
                    ProviderFailureType.UNAVAILABLE,
                    "The provider is unavailable.");
        }
        if (exception instanceof ValidationException) {
            return new ProviderFailureException(
                    ProviderFailureType.REQUEST_REJECTED,
                    "The provider rejected the bounded request.");
        }
        if (exception instanceof SdkClientException) {
            boolean timeout = hasCause(exception, java.net.SocketTimeoutException.class)
                    || hasCause(exception, java.util.concurrent.TimeoutException.class);
            return new ProviderFailureException(
                    timeout ? ProviderFailureType.TIMEOUT : ProviderFailureType.UNAVAILABLE,
                    timeout ? "The provider transport timed out." : "The provider connection failed.",
                    null,
                    exception);
        }
        return new ProviderFailureException(
                ProviderFailureType.UNAVAILABLE,
                "The provider transport failed.",
                null,
                exception);
    }

    private Document toDocument(JsonNode node) {
        try {
            return jsonNodeToDocument(node);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse("The configured JSON schema could not be converted for the provider.");
        }
    }

    // The AWS SDK Document type is an interface with no Jackson databind
    // creator, so it cannot be produced via ObjectMapper.convertValue. Walk the
    // JSON tree and build the Document explicitly through its factory methods.
    private Document jsonNodeToDocument(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Document.fromNull();
        }
        if (node.isTextual()) {
            return Document.fromString(node.textValue());
        }
        if (node.isBoolean()) {
            return Document.fromBoolean(node.booleanValue());
        }
        if (node.isNumber()) {
            return node.isIntegralNumber()
                    ? Document.fromNumber(SdkNumber.fromBigInteger(node.bigIntegerValue()))
                    : Document.fromNumber(SdkNumber.fromBigDecimal(node.decimalValue()));
        }
        if (node.isArray()) {
            List<Document> items = new ArrayList<>(node.size());
            for (JsonNode child : node) {
                items.add(jsonNodeToDocument(child));
            }
            return Document.fromList(items);
        }
        if (node.isObject()) {
            Map<String, Document> members = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry ->
                    members.put(entry.getKey(), jsonNodeToDocument(entry.getValue())));
            return Document.fromMap(members);
        }
        throw new IllegalArgumentException("Unsupported JSON node type: " + node.getNodeType());
    }

    // Reverse of jsonNodeToDocument: the SDK Document returned in the tool-use
    // block likewise has no Jackson databind serialiser, so rebuild the JSON
    // tree explicitly.
    private JsonNode fromDocument(Document document) {
        return documentToJsonNode(document);
    }

    private JsonNode documentToJsonNode(Document document) {
        JsonNodeFactory factory = JsonNodeFactory.instance;
        if (document == null || document.isNull()) {
            return factory.nullNode();
        }
        if (document.isString()) {
            return factory.textNode(document.asString());
        }
        if (document.isBoolean()) {
            return factory.booleanNode(document.asBoolean());
        }
        if (document.isNumber()) {
            return factory.numberNode(document.asNumber().bigDecimalValue());
        }
        if (document.isList()) {
            ArrayNode array = factory.arrayNode();
            for (Document item : document.asList()) {
                array.add(documentToJsonNode(item));
            }
            return array;
        }
        if (document.isMap()) {
            ObjectNode object = factory.objectNode();
            document.asMap().forEach((key, value) -> object.set(key, documentToJsonNode(value)));
            return object;
        }
        throw invalidResponse("The provider structured output could not be serialised.");
    }

    private String safeRequestId(ConverseResponse response) {
        String requestId = Optional.ofNullable(response.responseMetadata())
                .map(metadata -> metadata.requestId())
                .orElse(null);
        if (!StringUtils.hasText(requestId) || requestId.length() > 200) {
            return "unavailable";
        }
        return requestId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private GenerationBoundaryException invalidResponse(String message) {
        return new GenerationBoundaryException(message);
    }
}
