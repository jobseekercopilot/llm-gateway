package com.jobseekercopilot.llmgateway.config;

import com.jobseekercopilot.generated.systemdataservice.api.FixtureControllerApi;
import com.jobseekercopilot.generated.systemdataservice.client.ApiClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SystemDataServiceApiConfig {

    @Bean
    FixtureControllerApi fixtureControllerApi(FixtureProperties fixtureProperties) {
        ApiClient apiClient = new ApiClient();
        apiClient.setBasePath(fixtureProperties.getSystemDataServiceUrl());
        return new FixtureControllerApi(apiClient);
    }
}
