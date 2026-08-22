package com.jobseekercopilot.llmgateway.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Provider-neutral generation request with separate trust boundaries",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public class GenerationRequest {

    @NotBlank
    @Pattern(regexp = "2\\.0", message = "contractVersion must be 2.0")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = "2.0", example = "2.0")
    private String contractVersion;

    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "[A-Z][A-Z0-9_]{1,63}", message = "task must be an uppercase stable identifier")
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "DOCUMENT_DRAFT")
    private String task;

    @NotBlank
    @Size(max = 12_000)
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Trusted task instructions owned and versioned by the calling domain",
            maxLength = 12_000
    )
    private String trustedInstructions;

    @NotBlank
    @Size(max = 170_000)
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Untrusted source data. Provider adapters keep it separate from trusted instructions.",
            maxLength = 170_000
    )
    private String untrustedInput;

    @Valid
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private GenerationOutputContract output;

    @Valid
    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private GenerationLimits limits;
}
