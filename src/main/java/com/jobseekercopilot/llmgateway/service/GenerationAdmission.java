package com.jobseekercopilot.llmgateway.service;

record GenerationAdmission(
        long estimatedInputTokens,
        int maxEstimatedInputTokens,
        int maxOutputTokens
) {
}
