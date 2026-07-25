package com.jobseekercopilot.llmgateway.exception;

public class GenerationBoundaryException extends RuntimeException {
    public GenerationBoundaryException(String message) {
        super(message);
    }
}
