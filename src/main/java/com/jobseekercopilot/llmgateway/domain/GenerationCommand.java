package com.jobseekercopilot.llmgateway.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;

public record GenerationCommand(
        String task,
        String trustedInstructions,
        String untrustedInput,
        GenerationOutputFormat outputFormat,
        String schemaId,
        String schemaVersion,
        JsonNode jsonSchema,
        int maxOutputTokens,
        double temperature
) {
}
