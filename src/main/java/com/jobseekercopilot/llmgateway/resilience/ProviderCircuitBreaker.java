package com.jobseekercopilot.llmgateway.resilience;

import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ProviderCircuitBreaker {
    private final int failureThreshold;
    private final Duration openDuration;
    private final Clock clock;

    private int consecutiveFailures;
    private Instant openUntil;
    private boolean halfOpenProbeInFlight;

    @Autowired
    public ProviderCircuitBreaker(OpenAiConfiguration configuration) {
        this(
                configuration.getCircuitFailureThreshold(),
                Duration.ofMillis(configuration.getCircuitOpenDuration()),
                Clock.systemUTC()
        );
    }

    ProviderCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.clock = clock;
    }

    public synchronized Permit acquirePermit() {
        Instant now = clock.instant();
        if (openUntil == null) {
            return new Permit(false);
        }
        if (now.isBefore(openUntil)) {
            throw circuitOpen(now);
        }
        if (halfOpenProbeInFlight) {
            throw new ProviderFailureException(
                    ProviderFailureType.CIRCUIT_OPEN,
                    "A provider recovery probe is already in progress.",
                    1L
            );
        }
        halfOpenProbeInFlight = true;
        return new Permit(true);
    }

    public synchronized void recordSuccess(Permit permit) {
        consecutiveFailures = 0;
        openUntil = null;
        halfOpenProbeInFlight = false;
    }

    public synchronized void recordFailure(Permit permit, ProviderFailureType failureType) {
        halfOpenProbeInFlight = false;
        if (!countsTowardsCircuit(failureType)) {
            return;
        }

        boolean opensImmediately = failureType == ProviderFailureType.AUTHENTICATION
                || failureType == ProviderFailureType.QUOTA_EXHAUSTED;
        if (opensImmediately || permit.recoveryProbe()) {
            open();
            return;
        }

        consecutiveFailures++;
        if (consecutiveFailures >= failureThreshold) {
            open();
        }
    }

    public synchronized ProviderCircuitSnapshot snapshot() {
        if (openUntil == null) {
            return new ProviderCircuitSnapshot(
                    ProviderCircuitState.CLOSED,
                    consecutiveFailures,
                    0
            );
        }

        Instant now = clock.instant();
        if (!now.isBefore(openUntil)) {
            return new ProviderCircuitSnapshot(
                    ProviderCircuitState.HALF_OPEN,
                    consecutiveFailures,
                    0
            );
        }
        return new ProviderCircuitSnapshot(
                ProviderCircuitState.OPEN,
                consecutiveFailures,
                secondsUntil(openUntil, now)
        );
    }

    private void open() {
        consecutiveFailures = Math.max(consecutiveFailures, failureThreshold);
        openUntil = clock.instant().plus(openDuration);
    }

    private ProviderFailureException circuitOpen(Instant now) {
        return new ProviderFailureException(
                ProviderFailureType.CIRCUIT_OPEN,
                "The provider circuit is open.",
                secondsUntil(openUntil, now)
        );
    }

    private long secondsUntil(Instant target, Instant now) {
        long millis = Math.max(1, Duration.between(now, target).toMillis());
        return Math.max(1, (millis + 999) / 1000);
    }

    private boolean countsTowardsCircuit(ProviderFailureType failureType) {
        return switch (failureType) {
            case AUTHENTICATION, RATE_LIMITED, QUOTA_EXHAUSTED, TIMEOUT,
                    UNAVAILABLE, CAPACITY_EXHAUSTED, INVALID_RESPONSE -> true;
            case REQUEST_REJECTED, CIRCUIT_OPEN, CANCELLED -> false;
        };
    }

    public record Permit(boolean recoveryProbe) {}
}
