package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "Provider-neutral token usage reported for this generation",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public record GenerationUsage(
        @Schema(example = "4200") long inputTokens,
        @Schema(example = "3100") long outputTokens,
        @Schema(example = "7300") long totalTokens
) {
}
