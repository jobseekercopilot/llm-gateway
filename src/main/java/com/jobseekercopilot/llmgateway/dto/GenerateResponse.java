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
@Schema(description = "Response containing generated content from the LLM provider")
public class GenerateResponse {

    @Schema(
            description = "The LLM provider that generated the response",
            example = "OPENAI",
            allowableValues = {"FIXTURE", "MOCK", "OPENAI"}
    )
    private String provider;

    @Schema(
            description = "The model used for generation",
            example = "gpt-4.1-mini"
    )
    private String model;

    @Schema(description = "Token usage reported by the LLM provider")
    private LlmUsage usage;

    @Schema(
            description = "The generated content returned by the LLM. " +
                          "May contain JSON text returned by OpenAI.",
            example = "{\"message\":\"Generated content from OpenAI\"}"
    )
    private String response;
}
