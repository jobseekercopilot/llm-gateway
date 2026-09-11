package com.jobseekercopilot.llmgateway.config;

import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationControlSafetyTest {

    @Test
    void fixtureAcceptsExplicitNonBillablePolicy() {
        assertDoesNotThrow(() -> safety(ExternalProviderMode.FIXTURE, validControls()).validate());
    }

    @Test
    void liveRejectsFixturePolicyAndZeroRates() {
        assertThrows(
                IllegalStateException.class,
                () -> safety(ExternalProviderMode.LIVE, validControls()).validate()
        );
    }

    @Test
    void liveAcceptsReviewedVersionedNonZeroRates() {
        GenerationControlProperties controls = validControls();
        controls.setModelDeploymentVersion("openai-gpt-model-policy-2026-07");
        controls.setPricingVersion("openai-public-pricing-2026-07-25");
        controls.setInputRateMicroUsdPerMillionTokens(2_500_000);
        controls.setOutputRateMicroUsdPerMillionTokens(10_000_000);
        controls.setModelId("configured-model");

        assertDoesNotThrow(() -> safety(ExternalProviderMode.LIVE, controls).validate());
    }

    @Test
    void liveRejectsModelThatDoesNotMatchTheOpenAiRequestConfiguration() {
        GenerationControlProperties controls = validControls();
        controls.setModelId("different-model");
        controls.setModelDeploymentVersion("openai-gpt-model-policy-2026-07");
        controls.setPricingVersion("openai-public-pricing-2026-07-25");
        controls.setInputRateMicroUsdPerMillionTokens(2_500_000);
        controls.setOutputRateMicroUsdPerMillionTokens(10_000_000);

        assertThrows(
                IllegalStateException.class,
                () -> safety(ExternalProviderMode.LIVE, controls).validate()
        );
    }

    @Test
    void invalidTaskCeilingFailsStartup() {
        GenerationControlProperties controls = validControls();
        controls.getTasks().get("DOCUMENT_DRAFT").setMaxOutputTokens(32769);

        assertThrows(
                IllegalStateException.class,
                () -> safety(ExternalProviderMode.FIXTURE, controls).validate()
        );
    }

    @Test
    void rejectsMoreThanOneAutomaticProviderRetry() {
        GenerationControlProperties controls = validControls();
        controls.setMaxAutomaticProviderRetries(2);

        assertThrows(
                IllegalStateException.class,
                () -> safety(
                        ExternalProviderMode.FIXTURE,
                        controls).validate());
    }

    private GenerationControlSafety safety(
            ExternalProviderMode mode,
            GenerationControlProperties controls
    ) {
        ExternalProviderProperties provider = new ExternalProviderProperties();
        provider.setMode(mode);
        OpenAiConfiguration openAiConfiguration = new OpenAiConfiguration();
        openAiConfiguration.setModel("configured-model");
        BedrockConfiguration bedrockConfiguration = new BedrockConfiguration();
        bedrockConfiguration.setModelId("configured-model");
        return new GenerationControlSafety(
                controls, provider, openAiConfiguration, bedrockConfiguration);
    }

    private GenerationControlProperties validControls() {
        GenerationControlProperties controls = new GenerationControlProperties();
        controls.setAdmissionPolicyVersion("document-generation-admission-2026-07");
        controls.setModelId("fixture-model");
        controls.setModelDeploymentVersion("fixture-model-deployment-1");
        controls.setPricingVersion("non-billable-fixture-1");
        controls.setInputTokenReserve(256);
        GenerationControlProperties.TaskLimit taskLimit =
                new GenerationControlProperties.TaskLimit();
        taskLimit.setMaxEstimatedInputTokens(60_000);
        taskLimit.setMaxOutputTokens(32768);
        controls.setTasks(Map.of("DOCUMENT_DRAFT", taskLimit));
        return controls;
    }
}
