package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.logging.CorrelationIdFilter;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.LlmUsage;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "LIVE", matchIfMissing = true)
public class OpenAiClient implements LlmProviderClient {

    private final OpenAiConfiguration openAiConfiguration;
    private final RestTemplate restTemplate;

    public OpenAiClient(OpenAiConfiguration openAiConfiguration) {
        this.openAiConfiguration = openAiConfiguration;
        this.restTemplate = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(openAiConfiguration.getConnectTimeout()))
                .setReadTimeout(Duration.ofMillis(openAiConfiguration.getReadTimeout()))
                .build();
    }

    @Override
    public OpenAiGenerationResult generate(GenerateRequest request) {
        long startedAt = System.nanoTime();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(openAiConfiguration.getApiKey());
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (StringUtils.hasText(correlationId)) {
            headers.set(CorrelationIdFilter.HEADER_NAME, correlationId);
        }

        Map<String, Object> requestBody = Map.of(
                "model", openAiConfiguration.getModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", request.getPrompt()
                )),
                "temperature", request.getTemperature(),
                "max_tokens", request.getMaxTokens()
        );

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        log.info("OpenAI provider request started model={} temperature={} maxTokens={}",
                openAiConfiguration.getModel(), request.getTemperature(), request.getMaxTokens());

        ResponseEntity<Map> response = restTemplate.postForEntity(
                openAiConfiguration.getEndpoint(), entity, Map.class);
        log.info("OpenAI provider returned status={} durationMs={}",
                response.getStatusCode().value(),
                (System.nanoTime() - startedAt) / 1_000_000);

        Map<String, Object> responseBody = response.getBody();
        if (responseBody == null) {
            throw new RuntimeException("Empty response from OpenAI");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in OpenAI response");
        }

        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("No message in OpenAI choice");
        }

        String content = (String) message.get("content");
        if (content == null) {
            throw new RuntimeException("No content in OpenAI message");
        }

        return new OpenAiGenerationResult(content.trim(), mapUsage(responseBody));
    }

    private LlmUsage mapUsage(Map<String, Object> responseBody) {
        Map<String, Object> usage = (Map<String, Object>) responseBody.get("usage");
        if (usage == null) {
            log.warn("OpenAI response did not include token usage");
            return LlmUsage.builder()
                    .provider("OPENAI")
                    .model(openAiConfiguration.getModel())
                    .inputTokens(0L)
                    .outputTokens(0L)
                    .totalTokens(0L)
                    .build();
        }

        Long inputTokens = numberToLong(usage.get("prompt_tokens"));
        Long outputTokens = numberToLong(usage.get("completion_tokens"));
        Long totalTokens = numberToLong(usage.get("total_tokens"));
        if (totalTokens == null && inputTokens != null && outputTokens != null) {
            totalTokens = inputTokens + outputTokens;
        }
        if (totalTokens == null) {
            log.warn("OpenAI token usage was present but missing total_tokens");
        }

        return LlmUsage.builder()
                .provider("OPENAI")
                .model(openAiConfiguration.getModel())
                .inputTokens(inputTokens == null ? 0L : inputTokens)
                .outputTokens(outputTokens == null ? 0L : outputTokens)
                .totalTokens(totalTokens == null ? 0L : totalTokens)
                .build();
    }

    private Long numberToLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}
