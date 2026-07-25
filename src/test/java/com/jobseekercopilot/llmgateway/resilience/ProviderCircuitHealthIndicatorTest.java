package com.jobseekercopilot.llmgateway.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jobseekercopilot.llmgateway.config.ExternalProviderMode;
import com.jobseekercopilot.llmgateway.config.ExternalProviderProperties;
import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

class ProviderCircuitHealthIndicatorTest {

    @Test
    void liveOpenCircuitMakesReadinessOutOfServiceWithoutPayloadDetails() {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.LIVE);
        OpenAiConfiguration configuration = new OpenAiConfiguration();
        configuration.setCircuitFailureThreshold(1);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(configuration);
        ProviderCircuitBreaker.Permit permit = circuit.acquirePermit();
        circuit.recordFailure(permit, ProviderFailureType.UNAVAILABLE);

        var health = new ProviderCircuitHealthIndicator(properties, circuit).health();

        assertEquals(Status.OUT_OF_SERVICE, health.getStatus());
        assertEquals(ProviderCircuitState.OPEN, health.getDetails().get("state"));
        assertEquals(0, health.getDetails().get("automaticRetries"));
    }

    @Test
    void fixtureModeStaysReadyWithoutOpeningExternalCalls() {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.FIXTURE);
        ProviderCircuitBreaker circuit = new ProviderCircuitBreaker(new OpenAiConfiguration());

        var health = new ProviderCircuitHealthIndicator(properties, circuit).health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(ExternalProviderMode.FIXTURE, health.getDetails().get("mode"));
    }
}
