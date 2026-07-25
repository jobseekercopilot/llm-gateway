package com.jobseekercopilot.llmgateway.service;

import com.jobseekercopilot.llmgateway.config.GenerationControlProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationAudit;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationLimitException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

@Component
public class GenerationControls {
    private static final BigInteger TOKENS_PER_RATE_UNIT = BigInteger.valueOf(1_000_000L);
    private static final String CURRENCY = "USD";

    private final GenerationControlProperties controls;

    public GenerationControls(GenerationControlProperties controls) {
        this.controls = controls;
    }

    GenerationAdmission admit(GenerationCommand command) {
        GenerationControlProperties.TaskLimit taskLimit = controls.getTasks().get(command.task());
        if (taskLimit == null) {
            throw new GenerationLimitException();
        }

        long estimatedInputTokens = estimateInputTokens(command);
        if (estimatedInputTokens > taskLimit.getMaxEstimatedInputTokens()
                || command.maxOutputTokens() > taskLimit.getMaxOutputTokens()) {
            throw new GenerationLimitException();
        }
        return new GenerationAdmission(
                estimatedInputTokens,
                taskLimit.getMaxEstimatedInputTokens(),
                taskLimit.getMaxOutputTokens()
        );
    }

    void validateActualUsage(
            GenerationCommand command,
            ProviderGenerationResult result,
            GenerationAdmission admission
    ) {
        if (!controls.getModelId().equals(result.modelId())
                || result.usage().inputTokens() > admission.maxEstimatedInputTokens()
                || result.usage().outputTokens() > command.maxOutputTokens()
                || result.usage().outputTokens() > admission.maxOutputTokens()) {
            throw new GenerationBoundaryException(
                    "The provider reported token usage outside the admitted generation policy.");
        }
    }

    GenerationAudit audit(ProviderGenerationResult result, GenerationAdmission admission) {
        return new GenerationAudit(
                result.modelId(),
                controls.getModelDeploymentVersion(),
                controls.getAdmissionPolicyVersion(),
                controls.getPricingVersion(),
                admission.estimatedInputTokens(),
                estimatedCostMicroUsd(
                        result.usage().inputTokens(),
                        result.usage().outputTokens()
                ),
                CURRENCY
        );
    }

    private long estimateInputTokens(GenerationCommand command) {
        try {
            long estimate = controls.getInputTokenReserve();
            estimate = Math.addExact(estimate, utf8Length(command.task()));
            estimate = Math.addExact(estimate, utf8Length(command.trustedInstructions()));
            estimate = Math.addExact(estimate, utf8Length(command.untrustedInput()));
            if (command.jsonSchema() != null) {
                estimate = Math.addExact(estimate, utf8Length(command.jsonSchema().toString()));
            }
            return estimate;
        } catch (ArithmeticException exception) {
            throw new GenerationLimitException();
        }
    }

    private long estimatedCostMicroUsd(long inputTokens, long outputTokens) {
        BigInteger inputCost = roundedUpCost(
                inputTokens,
                controls.getInputRateMicroUsdPerMillionTokens()
        );
        BigInteger outputCost = roundedUpCost(
                outputTokens,
                controls.getOutputRateMicroUsdPerMillionTokens()
        );
        try {
            return inputCost.add(outputCost).longValueExact();
        } catch (ArithmeticException exception) {
            throw new GenerationBoundaryException(
                    "The provider usage produced an invalid cost estimate.");
        }
    }

    private BigInteger roundedUpCost(long tokens, long rate) {
        if (tokens == 0 || rate == 0) {
            return BigInteger.ZERO;
        }
        BigInteger numerator = BigInteger.valueOf(tokens).multiply(BigInteger.valueOf(rate));
        return numerator.add(TOKENS_PER_RATE_UNIT).subtract(BigInteger.ONE)
                .divide(TOKENS_PER_RATE_UNIT);
    }

    private int utf8Length(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }
}
