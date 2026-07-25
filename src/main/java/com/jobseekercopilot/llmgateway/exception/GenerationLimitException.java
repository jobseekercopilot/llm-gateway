package com.jobseekercopilot.llmgateway.exception;

public class GenerationLimitException extends RuntimeException {
    public GenerationLimitException() {
        super("The request exceeds the configured generation policy.");
    }
}
