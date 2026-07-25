package com.jobseekercopilot.llmgateway.resilience;

public enum ProviderCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
