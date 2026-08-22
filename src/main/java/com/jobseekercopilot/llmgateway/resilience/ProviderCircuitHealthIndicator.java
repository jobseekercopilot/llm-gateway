package com.jobseekercopilot.llmgateway.resilience;

import com.jobseekercopilot.llmgateway.config.ExternalProviderMode;
import com.jobseekercopilot.llmgateway.config.ExternalProviderProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("providerCircuit")
public class ProviderCircuitHealthIndicator implements HealthIndicator {
    private final ExternalProviderProperties providerProperties;
    private final ProviderCircuitBreaker circuitBreaker;

    public ProviderCircuitHealthIndicator(
            ExternalProviderProperties providerProperties,
            ProviderCircuitBreaker circuitBreaker
    ) {
        this.providerProperties = providerProperties;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public Health health() {
        ExternalProviderMode mode = providerProperties.getMode();
        ProviderCircuitSnapshot snapshot = circuitBreaker.snapshot();
        Health.Builder health = mode == ExternalProviderMode.LIVE
                && snapshot.state() == ProviderCircuitState.OPEN
                ? Health.outOfService()
                : Health.up();

        return health
                .withDetail("mode", mode)
                .withDetail("state", snapshot.state())
                .withDetail("consecutiveFailures", snapshot.consecutiveFailures())
                .withDetail("retryAfterSeconds", snapshot.retryAfterSeconds())
                .withDetail("automaticRetries", 0)
                .build();
    }
}
