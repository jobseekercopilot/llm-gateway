package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Token usage reported by the LLM provider")
public class LlmUsage {
    @Schema(example = "OPENAI")
    private String provider;

    @Schema(example = "gpt-4.1-mini")
    private String model;

    @Schema(example = "4200", nullable = true)
    private Long inputTokens;

    @Schema(example = "3100", nullable = true)
    private Long outputTokens;

    @Schema(example = "7300", nullable = true)
    private Long totalTokens;
}
