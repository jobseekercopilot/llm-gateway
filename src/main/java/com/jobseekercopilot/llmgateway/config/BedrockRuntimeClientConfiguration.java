package com.jobseekercopilot.llmgateway.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Builds the {@link BedrockRuntimeClient} used by {@link
 * com.jobseekercopilot.llmgateway.client.BedrockClient}. Only created when
 * Bedrock is the active external provider. Credentials resolve through the
 * default AWS credential chain (IAM role, environment credentials or SSO), so
 * no static key is stored in configuration.
 */
@Configuration
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "BEDROCK")
public class BedrockRuntimeClientConfiguration {

    @Bean
    BedrockRuntimeClient bedrockRuntimeClient(BedrockConfiguration configuration) {
        if (!StringUtils.hasText(configuration.getRegion())) {
            throw new IllegalStateException("BEDROCK mode requires an explicit AWS region.");
        }

        var builder = BedrockRuntimeClient.builder()
                .region(Region.of(configuration.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(overrideConfiguration(configuration))
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(
                                Duration.ofMillis(configuration.getConnectTimeout()))
                        .socketTimeout(socketTimeout(configuration)));

        if (StringUtils.hasText(configuration.getEndpointOverride())) {
            builder.endpointOverride(URI.create(configuration.getEndpointOverride()));
        }

        return builder.build();
    }

    /**
     * The response read deadline for one provider invocation.
     *
     * <p>This must be set explicitly. The configured call timeout only bounds
     * the SDK's own API-call timers; with no socket timeout the HTTP client
     * keeps {@code SdkHttpConfigurationOption.DEFAULT_SOCKET_READ_TIMEOUT} of
     * 30 seconds, which silently becomes the real ceiling. A generation still
     * streaming at 30 seconds was then killed mid-response no matter what this
     * configuration said.
     */
    static Duration socketTimeout(BedrockConfiguration configuration) {
        return Duration.ofMillis(configuration.getCallTimeout());
    }

    /**
     * One logical generation must be exactly one provider invocation.
     *
     * <p>The SDK's default policy retries a read timeout up to four attempts
     * internally and surfaces a single failure. That multiplies model cost
     * invisibly and destroys the caller's ability to reason about whether
     * anything was generated. Retry policy belongs to {@code LlmGatewayService},
     * which retries only provably-refused failures.
     */
    static ClientOverrideConfiguration overrideConfiguration(
            BedrockConfiguration configuration) {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(configuration.getCallTimeout()))
                .apiCallAttemptTimeout(Duration.ofMillis(configuration.getCallTimeout()))
                .retryPolicy(RetryPolicy.none())
                .build();
    }
}
