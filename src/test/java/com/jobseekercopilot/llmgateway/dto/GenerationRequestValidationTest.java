package com.jobseekercopilot.llmgateway.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

class GenerationRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory()
            .getValidator();

    @Test
    void acceptsUntrustedInputAtExpandedBoundary() {
        assertFalse(hasUntrustedInputViolation(request(170_000)));
    }

    @Test
    void rejectsUntrustedInputAboveExpandedBoundary() {
        assertTrue(hasUntrustedInputViolation(request(170_001)));
    }

    private boolean hasUntrustedInputViolation(GenerationRequest request) {
        return validator.validate(request).stream()
                .anyMatch(violation -> violation.getPropertyPath().toString()
                        .equals("untrustedInput"));
    }

    private GenerationRequest request(int untrustedInputLength) {
        return new GenerationRequest(
                "2.0",
                "CV_COVER_LETTER_GENERATION",
                "Use approved facts only.",
                "x".repeat(untrustedInputLength),
                new GenerationOutputContract(
                        GenerationOutputFormat.TEXT,
                        null,
                        null,
                        null),
                new GenerationLimits(3_000, 0.0));
    }
}
