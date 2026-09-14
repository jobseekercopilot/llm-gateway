package com.jobseekercopilot.llmgateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpConfigurationOption;

/**
 * Live Bedrock generations were failing at a hard ~120s while every success
 * completed in under 28s. The cause was configuration that looked bounded but
 * was not: the SDK's 30s default socket read timeout cut long generations
 * mid-response, and its default retry policy then repeated the cut four times.
 * These tests pin both invariants because neither is visible at runtime until
 * a generation is lost.
 */
class BedrockRuntimeClientConfigurationTest {

    @Test
    void socketTimeoutCoversTheWholeConfiguredCallDeadline() {
        BedrockConfiguration configuration = new BedrockConfiguration();

        assertEquals(
                Duration.ofMillis(configuration.getCallTimeout()),
                BedrockRuntimeClientConfiguration.socketTimeout(configuration));
    }

    @Test
    void socketTimeoutIsNotLeftAtTheSdkDefault() {
        BedrockConfiguration configuration = new BedrockConfiguration();
        Duration sdkDefault = SdkHttpConfigurationOption.GLOBAL_HTTP_DEFAULTS
                .get(SdkHttpConfigurationOption.READ_TIMEOUT);

        // The SDK default is 30s. A CV/cover-letter generation legitimately
        // takes tens of seconds, so inheriting this default silently truncates
        // real responses.
        assertEquals(Duration.ofSeconds(30), sdkDefault);
        assertNotEquals(
                sdkDefault,
                BedrockRuntimeClientConfiguration.socketTimeout(configuration));
        assertTrue(
                BedrockRuntimeClientConfiguration.socketTimeout(configuration)
                        .compareTo(sdkDefault) > 0);
    }

    @Test
    void oneGenerationIsExactlyOneProviderInvocation() {
        ClientOverrideConfiguration override =
                BedrockRuntimeClientConfiguration.overrideConfiguration(
                        new BedrockConfiguration());

        // Retry policy belongs to LlmGatewayService. Hidden SDK retries both
        // multiply model cost and make it impossible to know whether a timed
        // out call generated anything.
        assertEquals(0, override.retryPolicy().orElseThrow().numRetries());
    }

    @Test
    void apiCallDeadlinesMatchTheConfiguredCallTimeout() {
        BedrockConfiguration configuration = new BedrockConfiguration();
        ClientOverrideConfiguration override =
                BedrockRuntimeClientConfiguration.overrideConfiguration(configuration);

        assertEquals(
                Duration.ofMillis(configuration.getCallTimeout()),
                override.apiCallTimeout().orElseThrow());
        assertEquals(
                Duration.ofMillis(configuration.getCallTimeout()),
                override.apiCallAttemptTimeout().orElseThrow());
    }
}
