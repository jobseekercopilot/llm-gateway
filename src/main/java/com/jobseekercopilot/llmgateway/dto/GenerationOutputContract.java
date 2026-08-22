package com.jobseekercopilot.llmgateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.util.StringUtils;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Requested output format and optional strict JSON Schema",
        additionalProperties = Schema.AdditionalPropertiesValue.FALSE
)
public class GenerationOutputContract {
    private static final int MAX_SCHEMA_CHARACTERS = 64_000;

    @NotNull
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    private GenerationOutputFormat format;

    @Size(max = 64)
    @Pattern(regexp = "[a-z][a-z0-9_-]{0,63}", message = "schemaId must be a lowercase stable identifier")
    @Schema(example = "cv-cover-letter-output")
    private String schemaId;

    @Size(max = 32)
    @Pattern(regexp = "[0-9]+\\.[0-9]+(?:\\.[0-9]+)?", message = "schemaVersion must be numeric semantic version")
    @Schema(example = "1.0")
    private String schemaVersion;

    @Schema(description = "Strict JSON Schema object. Required only when format is JSON_SCHEMA.")
    private JsonNode jsonSchema;

    @JsonIgnore
    @Schema(hidden = true)
    @AssertTrue(message = "schemaId, schemaVersion and an object jsonSchema are required only for JSON_SCHEMA and the schema must not exceed 64000 characters")
    public boolean isSchemaConfigurationValid() {
        if (format == null) {
            return true;
        }
        if (format == GenerationOutputFormat.TEXT) {
            return !StringUtils.hasText(schemaId)
                    && !StringUtils.hasText(schemaVersion)
                    && (jsonSchema == null || jsonSchema.isNull());
        }
        return StringUtils.hasText(schemaId)
                && StringUtils.hasText(schemaVersion)
                && jsonSchema != null
                && jsonSchema.isObject()
                && jsonSchema.toString().length() <= MAX_SCHEMA_CHARACTERS;
    }
}
