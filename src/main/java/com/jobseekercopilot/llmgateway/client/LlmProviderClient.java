package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;

public interface LlmProviderClient {
    ProviderGenerationResult generate(GenerationCommand command);
}
