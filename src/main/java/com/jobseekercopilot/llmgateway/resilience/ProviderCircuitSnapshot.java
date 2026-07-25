package com.jobseekercopilot.llmgateway.resilience;

public record ProviderCircuitSnapshot(
        ProviderCircuitState state,
        int consecutiveFailures,
        long retryAfterSeconds
) {}
