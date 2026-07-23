package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.LlmUsage;
import com.jobseekercopilot.generated.systemdataservice.api.FixtureControllerApi;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmRequest;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "FIXTURE")
public class FixtureLlmProviderClient implements LlmProviderClient {
    private static final Logger log = LoggerFactory.getLogger(FixtureLlmProviderClient.class);
    private final FixtureProperties fixtureProperties;
    private final FixtureControllerApi fixtureControllerApi;

    public FixtureLlmProviderClient(FixtureProperties fixtureProperties, FixtureControllerApi fixtureControllerApi) {
        this.fixtureProperties = fixtureProperties;
        this.fixtureControllerApi = fixtureControllerApi;
    }

    @Override
    public OpenAiGenerationResult generate(GenerateRequest request) {
        FixtureLlmRequest payload = new FixtureLlmRequest()
                .datasetId(fixtureProperties.getDatasetId())
                .datasetVersion(fixtureProperties.getDatasetVersion())
                .scenario(fixtureProperties.getScenario())
                .operation(request.getTaskType() == null ? "GENERAL" : request.getTaskType());
        FixtureLlmResponse body = fixtureControllerApi.llm(payload);
        log.info("LLM fixture response returned datasetId={} scenario={}", fixtureProperties.getDatasetId(), fixtureProperties.getScenario());
        return new OpenAiGenerationResult(text(body == null ? null : body.getResponse(), "{}"), LlmUsage.builder()
                .provider(text(body == null ? null : body.getProvider(), "FIXTURE"))
                .model(text(body == null ? null : body.getModel(), "fixture-llm"))
                .inputTokens(number(body == null ? null : body.getInputTokens()))
                .outputTokens(number(body == null ? null : body.getOutputTokens()))
                .totalTokens(number(body == null ? null : body.getTotalTokens()))
                .build());
    }

    private String text(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }
}
