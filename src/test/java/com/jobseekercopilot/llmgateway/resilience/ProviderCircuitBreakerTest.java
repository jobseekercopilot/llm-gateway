package com.jobseekercopilot.llmgateway.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ProviderCircuitBreakerTest {

    @Test
    void opensAfterThresholdAllowsOneRecoveryProbeAndClosesOnSuccess() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T12:00:00Z"));
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(
                2,
                Duration.ofSeconds(30),
                clock
        );

        ProviderCircuitBreaker.Permit first = circuit.acquirePermit();
        assertFalse(first.recoveryProbe());
        circuit.recordFailure(first, ProviderFailureType.UNAVAILABLE);
        assertEquals(ProviderCircuitState.CLOSED, circuit.snapshot().state());

        ProviderCircuitBreaker.Permit second = circuit.acquirePermit();
        circuit.recordFailure(second, ProviderFailureType.TIMEOUT);
        assertEquals(ProviderCircuitState.OPEN, circuit.snapshot().state());
        ProviderFailureException open = assertThrows(
                ProviderFailureException.class,
                circuit::acquirePermit
        );
        assertEquals(ProviderFailureType.CIRCUIT_OPEN, open.getType());
        assertEquals(30L, open.getRetryAfterSeconds());

        clock.advance(Duration.ofSeconds(30));
        assertEquals(ProviderCircuitState.HALF_OPEN, circuit.snapshot().state());
        ProviderCircuitBreaker.Permit recoveryProbe = circuit.acquirePermit();
        assertTrue(recoveryProbe.recoveryProbe());
        assertThrows(ProviderFailureException.class, circuit::acquirePermit);

        circuit.recordSuccess(recoveryProbe);
        assertEquals(ProviderCircuitState.CLOSED, circuit.snapshot().state());
        assertEquals(0, circuit.snapshot().consecutiveFailures());
    }

    @Test
    void authenticationAndQuotaFailuresOpenImmediatelyButRejectedInputDoesNot() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-25T12:00:00Z"));
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(
                3,
                Duration.ofSeconds(30),
                clock
        );

        ProviderCircuitBreaker.Permit rejected = circuit.acquirePermit();
        circuit.recordFailure(rejected, ProviderFailureType.REQUEST_REJECTED);
        assertEquals(ProviderCircuitState.CLOSED, circuit.snapshot().state());

        ProviderCircuitBreaker.Permit authentication = circuit.acquirePermit();
        circuit.recordFailure(authentication, ProviderFailureType.AUTHENTICATION);
        assertEquals(ProviderCircuitState.OPEN, circuit.snapshot().state());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
