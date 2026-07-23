package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Request to generate content via OpenAI")
public class GenerateRequest {

    @Schema(
            description = "Informational field indicating the type of task. " +
                          "The gateway does not make decisions based on this value.",
            example = "CV_GENERATION"
    )
    private String taskType;

    @NotBlank(message = "prompt is required and cannot be empty")
    @Schema(
            description = "The fully assembled prompt to send to OpenAI. " +
                          "The gateway does not modify or build prompts.",
            example = "Generate a CV using the following information...",
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String prompt;

    @Min(value = 0, message = "temperature must be >= 0.0")
    @Max(value = 2, message = "temperature must be <= 2.0")
    @Schema(
            description = "Controls randomness of the output. Higher values produce more random results.",
            example = "0.3",
            defaultValue = "0.3",
            minimum = "0.0",
            maximum = "2.0"
    )
    private Double temperature = 0.3;

    @Min(value = 1, message = "maxTokens must be positive")
    @Schema(
            description = "Maximum number of tokens in the generated response.",
            example = "3000",
            defaultValue = "3000",
            minimum = "1"
    )
    private Integer maxTokens = 3000;
}
