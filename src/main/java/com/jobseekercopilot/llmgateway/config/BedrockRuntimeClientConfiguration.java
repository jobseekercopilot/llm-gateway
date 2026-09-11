package com.jobseekercopilot.llmgateway.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
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

        ClientOverrideConfiguration overrideConfiguration = ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofMillis(configuration.getCallTimeout()))
                .apiCallAttemptTimeout(Duration.ofMillis(configuration.getCallTimeout()))
                .build();

        var builder = BedrockRuntimeClient.builder()
                .region(Region.of(configuration.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(overrideConfiguration)
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofMillis(configuration.getConnectTimeout())));

        if (StringUtils.hasText(configuration.getEndpointOverride())) {
            builder.endpointOverride(URI.create(configuration.getEndpointOverride()));
        }

        return builder.build();
    }
}
