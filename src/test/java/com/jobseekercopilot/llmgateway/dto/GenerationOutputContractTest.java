package com.jobseekercopilot.llmgateway.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class GenerationOutputContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void acceptsSchemaAtExpandedBoundary() {
        assertTrue(contractWithSchemaLength(64_000).isSchemaConfigurationValid());
    }

    @Test
    void rejectsSchemaAboveExpandedBoundary() {
        assertFalse(contractWithSchemaLength(64_001).isSchemaConfigurationValid());
    }

    private GenerationOutputContract contractWithSchemaLength(int targetLength) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("padding", "");
        int baseLength = schema.toString().length();
        schema.put("padding", "x".repeat(targetLength - baseLength));
        return new GenerationOutputContract(
                GenerationOutputFormat.JSON_SCHEMA,
                "cv-cover-letter-output",
                "3.8.0",
                schema);
    }
}
