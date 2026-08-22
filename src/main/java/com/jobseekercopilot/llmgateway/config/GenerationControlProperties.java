package com.jobseekercopilot.llmgateway.config;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "generation-controls")
public class GenerationControlProperties {
    private String admissionPolicyVersion;
    private String modelId;
    private String modelDeploymentVersion;
    private String pricingVersion;
    private long inputRateMicroUsdPerMillionTokens;
    private long outputRateMicroUsdPerMillionTokens;
    private int inputTokenReserve;
    private int maxAutomaticProviderRetries = 1;
    private long providerRetryDelayMillis = 1000;
    private Map<String, TaskLimit> tasks = new LinkedHashMap<>();

    @Data
    public static class TaskLimit {
        private int maxEstimatedInputTokens;
        private int maxOutputTokens;
    }
}
