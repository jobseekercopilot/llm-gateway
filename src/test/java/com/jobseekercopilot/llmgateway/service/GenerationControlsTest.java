package com.jobseekercopilot.llmgateway.service;

import com.jobseekercopilot.llmgateway.config.GenerationControlProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationAudit;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationLimitException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationControlsTest {

    @Test
    void admitsConfiguredTaskAndCalculatesRoundedUpCostFromActualTokens() {
        GenerationControls controls = new GenerationControls(properties(1000, 100));
        GenerationAdmission admission = controls.admit(command("DOCUMENT_DRAFT", "é", 100));
        ProviderGenerationResult result = result(new GenerationUsage(1, 1, 2));

        GenerationAudit audit = controls.audit(result, admission);

        assertEquals(13L, audit.estimatedCostMicroUsd());
        assertEquals("test-pricing-1", audit.pricingVersion());
        assertEquals("USD", audit.currency());
        assertEquals(1 + "DOCUMENT_DRAFT".getBytes(StandardCharsets.UTF_8).length
                + "é".getBytes(StandardCharsets.UTF_8).length
                + "input".getBytes(StandardCharsets.UTF_8).length,
                audit.estimatedInputTokensAtAdmission());
    }

    @Test
    void rejectsUnknownTaskAndConservativeInputEstimateBeforeProviderUse() {
        GenerationControls controls = new GenerationControls(properties(10, 100));

        assertThrows(
                GenerationLimitException.class,
                () -> controls.admit(command("UNKNOWN_TASK", "safe", 100))
        );
        assertThrows(
                GenerationLimitException.class,
                () -> controls.admit(command("DOCUMENT_DRAFT", "éééé", 100))
        );
    }

    @Test
    void rejectsActualUsageOutsideTheAdmittedPolicy() {
        GenerationControls controls = new GenerationControls(properties(1000, 100));
        GenerationCommand command = command("DOCUMENT_DRAFT", "safe", 100);
        GenerationAdmission admission = controls.admit(command);

        assertThrows(
                GenerationBoundaryException.class,
                () -> controls.validateActualUsage(
                        command,
                        result(new GenerationUsage(1001, 1, 1002)),
                        admission
                )
        );
        assertThrows(
                GenerationBoundaryException.class,
                () -> controls.validateActualUsage(
                        command,
                        result(new GenerationUsage(1, 101, 102)),
                        admission
                )
        );
    }

    @Test
    void rejectsProviderModelOutsideTheReviewedPricingPolicy() {
        GenerationControls controls = new GenerationControls(properties(1000, 100));
        GenerationCommand command = command("DOCUMENT_DRAFT", "safe", 100);
        GenerationAdmission admission = controls.admit(command);
        ProviderGenerationResult unexpectedModel = new ProviderGenerationResult(
                "content",
                new GenerationUsage(1, 1, 2),
                GenerationFinishReason.COMPLETED,
                "fixture",
                "different-model"
        );

        assertThrows(
                GenerationBoundaryException.class,
                () -> controls.validateActualUsage(command, unexpectedModel, admission)
        );
    }

    private GenerationCommand command(String task, String trustedInstructions, int outputTokens) {
        return new GenerationCommand(
                task,
                trustedInstructions,
                "input",
                GenerationOutputFormat.TEXT,
                null,
                null,
                null,
                outputTokens,
                0.3
        );
    }

    private ProviderGenerationResult result(GenerationUsage usage) {
        return new ProviderGenerationResult(
                "content",
                usage,
                GenerationFinishReason.COMPLETED,
                "fixture",
                "fixture-model"
        );
    }

    private GenerationControlProperties properties(int maxInputTokens, int maxOutputTokens) {
        GenerationControlProperties properties = new GenerationControlProperties();
        properties.setAdmissionPolicyVersion("test-admission-1");
        properties.setModelId("fixture-model");
        properties.setModelDeploymentVersion("test-deployment-1");
        properties.setPricingVersion("test-pricing-1");
        properties.setInputRateMicroUsdPerMillionTokens(2_500_000);
        properties.setOutputRateMicroUsdPerMillionTokens(10_000_000);
        properties.setInputTokenReserve(1);
        GenerationControlProperties.TaskLimit taskLimit =
                new GenerationControlProperties.TaskLimit();
        taskLimit.setMaxEstimatedInputTokens(maxInputTokens);
        taskLimit.setMaxOutputTokens(maxOutputTokens);
        properties.setTasks(Map.of("DOCUMENT_DRAFT", taskLimit));
        return properties;
    }
}
