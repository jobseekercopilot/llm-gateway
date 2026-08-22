package com.jobseekercopilot.llmgateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "openai")
public class OpenAiConfiguration {

    private String apiKey;
    private String model;
    private String endpoint = "https://api.openai.com/v1/chat/completions";
    private String organizationId;
    private String projectId;
    private OpenAiDataRegion dataRegion;
    private OpenAiDataControlMode dataControlMode;
    private OpenAiDataSharingMode dataSharingMode;
    private String privacyPolicyVersion;
    private String privacyDecisionId;
    private String privacyOwner;
    private String privacyReviewOn;
    private int connectTimeout = 10000;
    private int callTimeout = 480000;
    private int maxResponseBytes = 1048576;
    private int maxConcurrentCalls = 8;
    private int circuitFailureThreshold = 3;
    private int circuitOpenDuration = 30000;
}
