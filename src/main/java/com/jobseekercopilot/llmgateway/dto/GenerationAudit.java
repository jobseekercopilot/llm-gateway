package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "Non-payload model deployment, admission and provider-cost audit metadata",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public record GenerationAudit(
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "gpt-4.1-mini-2025-04-14"
        ) String modelId,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "document-generation-model-2026-07"
        ) String modelDeploymentVersion,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "document-generation-admission-2026-07"
        ) String admissionPolicyVersion,
        @Schema(
                requiredMode = Schema.RequiredMode.REQUIRED,
                example = "openai-public-pricing-2026-07-25"
        ) String pricingVersion,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "5372")
        long estimatedInputTokensAtAdmission,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "41400")
        long estimatedCostMicroUsd,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "USD")
        String currency,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        int providerAttemptCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        int automaticRetryCount,
        @Schema(example = "RATE_LIMITED")
        String retryReason
) {
}
