package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
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
    public ProviderGenerationResult generate(GenerationCommand command) {
        FixtureLlmRequest payload = new FixtureLlmRequest()
                .datasetId(fixtureProperties.getDatasetId())
                .datasetVersion(fixtureProperties.getDatasetVersion())
                .scenario(fixtureProperties.getScenario())
                .operation(command.task());
        FixtureLlmResponse body = fixtureControllerApi.llm(payload);
        log.info("LLM fixture response returned datasetId={} scenario={}", fixtureProperties.getDatasetId(), fixtureProperties.getScenario());
        return new ProviderGenerationResult(
                text(body == null ? null : body.getResponse(), "{}"),
                new GenerationUsage(
                        number(body == null ? null : body.getInputTokens()),
                        number(body == null ? null : body.getOutputTokens()),
                        number(body == null ? null : body.getTotalTokens())
                ),
                GenerationFinishReason.COMPLETED,
                text(body == null ? null : body.getProvider(), "fixture"),
                text(body == null ? null : body.getModel(), "fixture-llm")
        );
    }

    private String text(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }
}
