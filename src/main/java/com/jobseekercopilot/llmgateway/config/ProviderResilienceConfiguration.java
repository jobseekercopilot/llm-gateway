package com.jobseekercopilot.llmgateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolves the {@link ProviderResilienceSettings} that the shared circuit
 * breaker and bounded call executor consume. When Bedrock is the active
 * external provider its tunables are used; otherwise the OpenAI tunables apply.
 * A single settings bean is always available so DISABLED and FIXTURE modes keep
 * their existing (OpenAI-derived) defaults.
 */
@Configuration
public class ProviderResilienceConfiguration {

    @Bean
    ProviderResilienceSettings providerResilienceSettings(
            ExternalProviderProperties providerProperties,
            OpenAiConfiguration openAiConfiguration,
            BedrockConfiguration bedrockConfiguration
    ) {
        if (providerProperties.getMode() == ExternalProviderMode.BEDROCK) {
            return ProviderResilienceSettings.fromBedrock(bedrockConfiguration);
        }
        return ProviderResilienceSettings.fromOpenAi(openAiConfiguration);
    }
}
