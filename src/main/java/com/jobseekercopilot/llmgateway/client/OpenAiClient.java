package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationRefusedException;
import com.jobseekercopilot.llmgateway.logging.CorrelationIdFilter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "LIVE")
public class OpenAiClient implements LlmProviderClient {
    static final String OPENAI_ORGANIZATION_HEADER = "OpenAI-Organization";
    static final String OPENAI_PROJECT_HEADER = "OpenAI-Project";
    static final String OPENAI_REQUEST_ID_HEADER = "x-request-id";

    private final OpenAiConfiguration openAiConfiguration;
    private final RestTemplate restTemplate;

    @Autowired
    public OpenAiClient(OpenAiConfiguration openAiConfiguration) {
        this(openAiConfiguration, new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(openAiConfiguration.getConnectTimeout()))
                .setReadTimeout(Duration.ofMillis(openAiConfiguration.getReadTimeout()))
                .build());
    }

    OpenAiClient(OpenAiConfiguration openAiConfiguration, RestTemplate restTemplate) {
        this.openAiConfiguration = openAiConfiguration;
        this.restTemplate = restTemplate;
    }

    @Override
    public ProviderGenerationResult generate(GenerationCommand command) {
        long startedAt = System.nanoTime();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiConfiguration.getApiKey());
        headers.set(OPENAI_ORGANIZATION_HEADER, openAiConfiguration.getOrganizationId());
        headers.set(OPENAI_PROJECT_HEADER, openAiConfiguration.getProjectId());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (StringUtils.hasText(correlationId)) {
            headers.set(CorrelationIdFilter.HEADER_NAME, correlationId);
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", openAiConfiguration.getModel());
        requestBody.put("messages", List.of(
                Map.of("role", "developer", "content", command.trustedInstructions()),
                Map.of("role", "user", "content", command.untrustedInput())
        ));
        requestBody.put("temperature", command.temperature());
        requestBody.put("max_completion_tokens", command.maxOutputTokens());
        requestBody.put("store", false);
        requestBody.put("service_tier", "default");
        if (command.outputFormat() == GenerationOutputFormat.JSON_SCHEMA) {
            requestBody.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", command.schemaId(),
                            "strict", true,
                            "schema", command.jsonSchema()
                    )
            ));
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("OpenAI provider request started model={} temperature={} maxTokens={}",
                openAiConfiguration.getModel(), command.temperature(), command.maxOutputTokens());

        ResponseEntity<Map> response = restTemplate.postForEntity(
                openAiConfiguration.getEndpoint(), entity, Map.class);
        log.info("OpenAI provider returned status={} durationMs={} providerRequestId={}",
                response.getStatusCode().value(),
                (System.nanoTime() - startedAt) / 1_000_000,
                safeRequestId(response));

        Map<?, ?> responseBody = response.getBody();
        if (responseBody == null) {
            throw invalidResponse("The provider response body was empty.");
        }
        String responseModel = requiredText(
                responseBody.get("model"),
                "The provider response did not contain a model ID."
        );
        String serviceTier = requiredText(
                responseBody.get("service_tier"),
                "The provider response did not contain a service tier."
        );
        if (!"default".equals(serviceTier)) {
            throw invalidResponse("The provider response used an unexpected service tier.");
        }

        Object choicesValue = responseBody.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()) {
            throw invalidResponse("The provider response did not contain a choice.");
        }

        if (!(choices.get(0) instanceof Map<?, ?> choice)) {
            throw invalidResponse("The provider choice was malformed.");
        }
        if (!(choice.get("message") instanceof Map<?, ?> message)) {
            throw invalidResponse("The provider choice did not contain a message.");
        }
        Object refusal = message.get("refusal");
        if ((refusal instanceof String refusalText && StringUtils.hasText(refusalText))
                || "content_filter".equals(choice.get("finish_reason"))) {
            throw new GenerationRefusedException();
        }

        if (!(message.get("content") instanceof String content) || !StringUtils.hasText(content)) {
            throw invalidResponse("The provider message did not contain output.");
        }

        return new ProviderGenerationResult(
                content.trim(),
                mapUsage(responseBody),
                mapFinishReason(choice.get("finish_reason")),
                "openai",
                responseModel
        );
    }

    private GenerationUsage mapUsage(Map<?, ?> responseBody) {
        if (!(responseBody.get("usage") instanceof Map<?, ?> usage)) {
            throw invalidResponse("The provider response did not contain token usage.");
        }

        Long inputTokens = numberToLong(usage.get("prompt_tokens"));
        Long outputTokens = numberToLong(usage.get("completion_tokens"));
        Long totalTokens = numberToLong(usage.get("total_tokens"));
        if (inputTokens == null || outputTokens == null) {
            throw invalidResponse("The provider token usage was incomplete.");
        }
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }

        return new GenerationUsage(inputTokens, outputTokens, totalTokens);
    }

    private GenerationFinishReason mapFinishReason(Object value) {
        if (!(value instanceof String finishReason)) {
            return GenerationFinishReason.UNKNOWN;
        }
        return switch (finishReason) {
            case "stop" -> GenerationFinishReason.COMPLETED;
            case "length" -> GenerationFinishReason.LIMIT_REACHED;
            case "content_filter" -> GenerationFinishReason.FILTERED;
            default -> GenerationFinishReason.UNKNOWN;
        };
    }

    private Long numberToLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    private GenerationBoundaryException invalidResponse(String message) {
        return new GenerationBoundaryException(message);
    }

    private String requiredText(Object value, String message) {
        if (!(value instanceof String text) || !StringUtils.hasText(text) || text.length() > 128) {
            throw invalidResponse(message);
        }
        return text;
    }

    private String safeRequestId(ResponseEntity<?> response) {
        String requestId = response.getHeaders().getFirst(OPENAI_REQUEST_ID_HEADER);
        if (!StringUtils.hasText(requestId) || requestId.length() > 200) {
            return "unavailable";
        }
        return requestId.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
