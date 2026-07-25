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
    private int connectTimeout = 10000;
    private int readTimeout = 60000;
}
