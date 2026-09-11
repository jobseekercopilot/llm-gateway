package com.jobseekercopilot.llmgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime configuration for the AWS Bedrock provider.
 *
 * <p>Bedrock authenticates with the ambient AWS credential chain (IAM role,
 * environment credentials, or SSO) rather than a static API key, so there is no
 * key property here. Model access is expressed as a Bedrock model id or an
 * inference-profile ARN. The timeout, concurrency and circuit-breaker tunables
 * mirror {@link OpenAiConfiguration} so the shared resilience beans behave
 * identically regardless of the active provider.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "bedrock")
public class BedrockConfiguration {

    /**
     * Bedrock model id or inference-profile identifier passed to the Converse
     * API, e.g. {@code eu.anthropic.claude-3-5-sonnet-20240620-v1:0}.
     */
    private String modelId;

    /** AWS region hosting the Bedrock runtime endpoint, e.g. {@code eu-west-2}. */
    private String region;

    /**
     * Optional override for the Bedrock runtime endpoint. Left blank in normal
     * operation so the SDK resolves the regional endpoint automatically.
     */
    private String endpointOverride;

    private int connectTimeout = 10000;
    private int callTimeout = 480000;
    private int maxResponseBytes = 1048576;
    private int maxConcurrentCalls = 8;
    private int circuitFailureThreshold = 3;
    private int circuitOpenDuration = 30000;
}
