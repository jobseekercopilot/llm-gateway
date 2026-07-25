package com.jobseekercopilot.llmgateway.exception;

public class GenerationRefusedException extends RuntimeException {
    public GenerationRefusedException() {
        super("The provider refused to generate this output.");
    }
}
