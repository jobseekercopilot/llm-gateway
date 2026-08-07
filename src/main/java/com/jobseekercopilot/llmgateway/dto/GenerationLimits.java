package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Explicit provider-neutral generation limits",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public class GenerationLimits {

    @NotNull
    @Min(value = 1, message = "maxOutputTokens must be at least 1")
    @Max(value = 8192, message = "maxOutputTokens must not exceed 8192")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1", maximum = "8192", example = "3000")
    private Integer maxOutputTokens;

    @NotNull
    @DecimalMin(value = "0.0", message = "temperature must be at least 0.0")
    @DecimalMax(value = "1.0", message = "temperature must not exceed 1.0")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "0.0", maximum = "1.0", example = "0.3")
    private Double temperature;
}
