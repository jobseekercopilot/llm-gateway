package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.dto.LlmUsage;

public record OpenAiGenerationResult(String content, LlmUsage usage) {
}
