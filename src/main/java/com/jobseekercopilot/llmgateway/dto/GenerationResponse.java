package com.jobseekercopilot.llmgateway.dto;

import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(
        description = "Provider-neutral generation result",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public record GenerationResponse(
        @Schema(example = "2.0") String contractVersion,
        String output,
        GenerationFinishReason finishReason,
        GenerationUsage usage,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GenerationAudit audit,
        String schemaId,
        String schemaVersion
) {
}
