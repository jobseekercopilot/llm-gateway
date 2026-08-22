package com.jobseekercopilot.llmgateway.domain;

import com.jobseekercopilot.llmgateway.dto.GenerationUsage;

public record ProviderGenerationResult(
        String output,
        GenerationUsage usage,
        GenerationFinishReason finishReason,
        String adapterId,
        String modelId,
        int providerAttemptCount,
        int automaticRetryCount,
        String retryReason
) {
    public ProviderGenerationResult(
            String output,
            GenerationUsage usage,
            GenerationFinishReason finishReason,
            String adapterId,
            String modelId) {
        this(output, usage, finishReason, adapterId, modelId, 1, 0, null);
    }

    public ProviderGenerationResult withAttemptAudit(
            int attempts,
            int retries,
            String reason) {
        return new ProviderGenerationResult(
                output,
                usage,
                finishReason,
                adapterId,
                modelId,
                attempts,
                retries,
                reason);
    }
}
