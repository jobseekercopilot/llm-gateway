package com.jobseekercopilot.llmgateway.config;

/**
 * Provider-neutral view of the resilience tunables shared by the circuit
 * breaker and the bounded call executor. Both {@link OpenAiConfiguration} and
 * {@link BedrockConfiguration} expose the same knobs; this abstraction lets the
 * shared resilience beans stay independent of the active provider.
 */
public record ProviderResilienceSettings(
        int maxConcurrentCalls,
        int callTimeout,
        int circuitFailureThreshold,
        int circuitOpenDuration
) {
    public static ProviderResilienceSettings fromOpenAi(OpenAiConfiguration configuration) {
        return new ProviderResilienceSettings(
                configuration.getMaxConcurrentCalls(),
                configuration.getCallTimeout(),
                configuration.getCircuitFailureThreshold(),
                configuration.getCircuitOpenDuration()
        );
    }

    public static ProviderResilienceSettings fromBedrock(BedrockConfiguration configuration) {
        return new ProviderResilienceSettings(
                configuration.getMaxConcurrentCalls(),
                configuration.getCallTimeout(),
                configuration.getCircuitFailureThreshold(),
                configuration.getCircuitOpenDuration()
        );
    }
}
