package com.jobseekercopilot.llmgateway.client;

import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.exception.ProviderDisabledException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "DISABLED", matchIfMissing = true)
public class DisabledLlmProviderClient implements LlmProviderClient {

    @Override
    public ProviderGenerationResult generate(GenerationCommand command) {
        throw new ProviderDisabledException();
    }
}
