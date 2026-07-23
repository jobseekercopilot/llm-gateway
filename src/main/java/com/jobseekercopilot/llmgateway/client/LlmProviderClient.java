package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.dto.GenerateRequest;

public interface LlmProviderClient {
    OpenAiGenerationResult generate(GenerateRequest request);
}
