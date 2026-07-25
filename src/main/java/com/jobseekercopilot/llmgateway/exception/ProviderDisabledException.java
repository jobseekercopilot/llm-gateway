package com.jobseekercopilot.llmgateway.exception;

public class ProviderDisabledException extends RuntimeException {
    public ProviderDisabledException() {
        super("External model generation is disabled.");
    }
}
