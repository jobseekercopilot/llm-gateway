package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "Deprecated v1 raw-prompt request retained during consumer migration")
public class GenerateRequest {

    @Size(max = 64)
    @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "taskType must be an uppercase stable identifier")
    @Schema(
            description = "Informational field indicating the type of task. " +
                          "The gateway does not make decisions based on this value.",
            example = "CV_GENERATION"
    )
    private String taskType;

    @NotBlank(message = "prompt is required and cannot be empty")
    @Size(max = 40_000, message = "prompt must not exceed 40000 characters")
    @Schema(
            description = "Deprecated fully assembled prompt. Migrate to the v2 trust-separated request.",
            example = "Generate a CV using the following information...",
            maxLength = 40_000,
            requiredMode = Schema.RequiredMode.REQUIRED
    )
    private String prompt;

    @DecimalMin(value = "0.0", message = "temperature must be >= 0.0")
    @DecimalMax(value = "1.0", message = "temperature must be <= 1.0")
    @Schema(
            description = "Controls randomness of the output. Higher values produce more random results.",
            example = "0.3",
            defaultValue = "0.3",
            minimum = "0.0",
            maximum = "1.0"
    )
    private Double temperature = 0.3;

    @Min(value = 1, message = "maxTokens must be positive")
    @Max(value = 4096, message = "maxTokens must not exceed 4096")
    @Schema(
            description = "Maximum number of tokens in the generated response.",
            example = "3000",
            defaultValue = "3000",
            minimum = "1",
            maximum = "4096"
    )
    private Integer maxTokens = 3000;
}
