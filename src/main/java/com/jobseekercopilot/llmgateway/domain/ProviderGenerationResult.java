package com.jobseekercopilot.llmgateway.domain;

import com.jobseekercopilot.llmgateway.dto.GenerationUsage;

public record ProviderGenerationResult(
        String output,
        GenerationUsage usage,
        GenerationFinishReason finishReason,
        String adapterId,
        String modelId
) {
}
