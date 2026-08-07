package com.jobseekercopilot.llmgateway.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationRefusedException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import com.jobseekercopilot.llmgateway.logging.CorrelationIdFilter;
import com.jobseekercopilot.llmgateway.resilience.ProviderCallExecutor;
import com.jobseekercopilot.llmgateway.resilience.ProviderCircuitBreaker;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "LIVE")
public class OpenAiClient implements LlmProviderClient {
    static final String OPENAI_ORGANIZATION_HEADER = "OpenAI-Organization";
    static final String OPENAI_PROJECT_HEADER = "OpenAI-Project";
    static final String OPENAI_REQUEST_ID_HEADER = "x-request-id";
    private static final ResponseErrorHandler NO_OP_ERROR_HANDLER = new ResponseErrorHandler() {
        @Override
        public boolean hasError(ClientHttpResponse response) {
            return false;
        }

        @Override
        public void handleError(ClientHttpResponse response) throws IOException {
            // Every status is mapped below after the response body has passed the byte limit.
        }
    };

    private final OpenAiConfiguration openAiConfiguration;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ProviderCircuitBreaker circuitBreaker;
    private final ProviderCallExecutor providerCallExecutor;

    @Autowired
    public OpenAiClient(
            OpenAiConfiguration openAiConfiguration,
            ObjectMapper objectMapper,
            ProviderCircuitBreaker circuitBreaker,
            ProviderCallExecutor providerCallExecutor
    ) {
        this(
                openAiConfiguration,
                new RestTemplateBuilder()
                        .setConnectTimeout(Duration.ofMillis(openAiConfiguration.getConnectTimeout()))
                        .setReadTimeout(Duration.ofMillis(openAiConfiguration.getCallTimeout()))
                        .errorHandler(NO_OP_ERROR_HANDLER)
                        .build(),
                objectMapper,
                circuitBreaker,
                providerCallExecutor
        );
    }

    OpenAiClient(OpenAiConfiguration openAiConfiguration, RestTemplate restTemplate) {
        this(
                openAiConfiguration,
                restTemplate,
                new ObjectMapper(),
                new ProviderCircuitBreaker(openAiConfiguration),
                providerCall -> {
                    try {
                        return providerCall.call();
                    } catch (RuntimeException exception) {
                        throw exception;
                    } catch (Exception exception) {
                        throw new IllegalStateException("Provider test call failed.", exception);
                    }
                }
        );
    }

    OpenAiClient(
            OpenAiConfiguration openAiConfiguration,
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            ProviderCircuitBreaker circuitBreaker,
            ProviderCallExecutor providerCallExecutor
    ) {
        this.openAiConfiguration = openAiConfiguration;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.circuitBreaker = circuitBreaker;
        this.providerCallExecutor = providerCallExecutor;
        this.restTemplate.setErrorHandler(NO_OP_ERROR_HANDLER);
        this.restTemplate.getMessageConverters()
                .removeIf(ByteArrayHttpMessageConverter.class::isInstance);
        this.restTemplate.getMessageConverters().add(
                0,
                new BoundedByteArrayHttpMessageConverter(openAiConfiguration.getMaxResponseBytes())
        );
    }

    @Override
    public ProviderGenerationResult generate(GenerationCommand command) {
        long startedAt = System.nanoTime();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiConfiguration.getApiKey());
        if (StringUtils.hasText(openAiConfiguration.getOrganizationId())) {
            headers.set(
                    OPENAI_ORGANIZATION_HEADER,
                    openAiConfiguration.getOrganizationId()
            );
        }
        if (StringUtils.hasText(openAiConfiguration.getProjectId())) {
            headers.set(OPENAI_PROJECT_HEADER, openAiConfiguration.getProjectId());
        }
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

        ProviderCircuitBreaker.Permit permit;
        try {
            permit = circuitBreaker.acquirePermit();
        } catch (ProviderFailureException exception) {
            log.warn("OpenAI provider call rejected failureType={} attempt=1 automaticRetries=0",
                    exception.getType());
            throw exception;
        }

        try {
            ResponseEntity<byte[]> response = providerCallExecutor.execute(
                    () -> restTemplate.postForEntity(
                            openAiConfiguration.getEndpoint(),
                            entity,
                            byte[].class
                    )
            );
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw mapFailureResponse(response);
            }
            Map<?, ?> responseBody = parseResponse(response.getBody());
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
                circuitBreaker.recordSuccess(permit);
                throw new GenerationRefusedException();
            }

            if (!(message.get("content") instanceof String content)
                    || !StringUtils.hasText(content)) {
                throw invalidResponse("The provider message did not contain output.");
            }

            ProviderGenerationResult result = new ProviderGenerationResult(
                    content.trim(),
                    mapUsage(responseBody),
                    mapFinishReason(choice.get("finish_reason")),
                    "openai",
                    responseModel
            );
            circuitBreaker.recordSuccess(permit);
            log.info("OpenAI provider returned status={} durationMs={} providerRequestId={} attempt=1 automaticRetries=0",
                    response.getStatusCode().value(),
                    (System.nanoTime() - startedAt) / 1_000_000,
                    safeRequestId(response));
            return result;
        } catch (ProviderFailureException exception) {
            circuitBreaker.recordFailure(permit, exception.getType());
            log.warn("OpenAI provider call failed failureType={} durationMs={} attempt=1 automaticRetries=0",
                    exception.getType(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        } catch (GenerationBoundaryException exception) {
            circuitBreaker.recordFailure(permit, ProviderFailureType.INVALID_RESPONSE);
            log.warn("OpenAI provider response rejected failureType={} durationMs={} attempt=1 automaticRetries=0",
                    ProviderFailureType.INVALID_RESPONSE,
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw exception;
        } catch (ResourceAccessException exception) {
            ProviderFailureException providerFailure = resourceFailure(exception);
            circuitBreaker.recordFailure(permit, providerFailure.getType());
            log.warn("OpenAI provider transport failed failureType={} durationMs={} attempt=1 automaticRetries=0",
                    providerFailure.getType(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw providerFailure;
        } catch (RestClientException exception) {
            ProviderFailureException nestedFailure = findProviderFailure(exception);
            ProviderFailureException providerFailure = nestedFailure == null
                    ? new ProviderFailureException(
                            ProviderFailureType.UNAVAILABLE,
                            "The provider transport failed.",
                            null,
                            exception
                    )
                    : nestedFailure;
            circuitBreaker.recordFailure(permit, providerFailure.getType());
            log.warn("OpenAI provider transport failed failureType={} durationMs={} attempt=1 automaticRetries=0",
                    providerFailure.getType(),
                    (System.nanoTime() - startedAt) / 1_000_000);
            throw providerFailure;
        }
    }

    private Map<?, ?> parseResponse(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            throw invalidResponse("The provider response body was empty.");
        }
        try {
            return objectMapper.readValue(responseBody, Map.class);
        } catch (IOException exception) {
            throw invalidResponse("The provider response was not valid JSON.");
        }
    }

    private ProviderFailureException mapFailureResponse(ResponseEntity<byte[]> response) {
        HttpStatusCode status = response.getStatusCode();
        Long retryAfterSeconds = retryAfterSeconds(response);
        ProviderErrorMetadata errorMetadata = providerErrorMetadata(response.getBody());
        log.warn(
                "OpenAI provider rejected request status={} errorType={} errorCode={} errorParam={}",
                status.value(),
                errorMetadata.type(),
                errorMetadata.code(),
                errorMetadata.param()
        );
        if (status.value() == 401 || status.value() == 403) {
            return new ProviderFailureException(
                    ProviderFailureType.AUTHENTICATION,
                    "The provider rejected its configured credentials."
            );
        }
        if (status.value() == 429) {
            ProviderFailureType type = isQuotaExhausted(response.getBody())
                    ? ProviderFailureType.QUOTA_EXHAUSTED
                    : ProviderFailureType.RATE_LIMITED;
            return new ProviderFailureException(
                    type,
                    type == ProviderFailureType.QUOTA_EXHAUSTED
                            ? "The provider account quota is exhausted."
                            : "The provider rate limit was exceeded.",
                    retryAfterSeconds
            );
        }
        if (status.value() == 408 || status.value() == 504) {
            return new ProviderFailureException(
                    ProviderFailureType.TIMEOUT,
                    "The provider timed out.",
                    retryAfterSeconds
            );
        }
        if (status.is5xxServerError()) {
            return new ProviderFailureException(
                    ProviderFailureType.UNAVAILABLE,
                    "The provider is unavailable.",
                    retryAfterSeconds
            );
        }
        return new ProviderFailureException(
                ProviderFailureType.REQUEST_REJECTED,
                "The provider rejected the bounded request."
        );
    }

    private ProviderErrorMetadata providerErrorMetadata(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return ProviderErrorMetadata.UNAVAILABLE;
        }
        try {
            JsonNode error = objectMapper.readTree(responseBody).path("error");
            return new ProviderErrorMetadata(
                    safeProviderToken(error.path("type")),
                    safeProviderToken(error.path("code")),
                    safeProviderToken(error.path("param"))
            );
        } catch (IOException exception) {
            return ProviderErrorMetadata.UNAVAILABLE;
        }
    }

    private String safeProviderToken(JsonNode value) {
        if (!value.isTextual()) {
            return "unavailable";
        }
        String token = value.asText();
        return token.matches("[A-Za-z0-9_.-]{1,80}") ? token : "unavailable";
    }

    private boolean isQuotaExhausted(byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            return "insufficient_quota".equals(root.path("error").path("code").asText());
        } catch (IOException exception) {
            return false;
        }
    }

    private Long retryAfterSeconds(ResponseEntity<?> response) {
        String value = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value);
            return seconds > 0 && seconds <= 3600 ? seconds : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ProviderFailureException resourceFailure(ResourceAccessException exception) {
        ProviderFailureType type = hasCause(exception, SocketTimeoutException.class)
                ? ProviderFailureType.TIMEOUT
                : ProviderFailureType.UNAVAILABLE;
        return new ProviderFailureException(
                type,
                type == ProviderFailureType.TIMEOUT
                        ? "The provider transport timed out."
                        : "The provider connection failed.",
                null,
                exception
        );
    }

    private ProviderFailureException findProviderFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ProviderFailureException providerFailure) {
                return providerFailure;
            }
            current = current.getCause();
        }
        return null;
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

    private record ProviderErrorMetadata(String type, String code, String param) {
        private static final ProviderErrorMetadata UNAVAILABLE =
                new ProviderErrorMetadata("unavailable", "unavailable", "unavailable");
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
