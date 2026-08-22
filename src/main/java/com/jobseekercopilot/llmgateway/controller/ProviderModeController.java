package com.jobseekercopilot.llmgateway.controller;

import com.jobseekercopilot.llmgateway.config.ExternalProviderMode;
import com.jobseekercopilot.llmgateway.config.ExternalProviderProperties;
import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import io.swagger.v3.oas.annotations.Hidden;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
@Hidden
public class ProviderModeController {
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;

    public ProviderModeController(ExternalProviderProperties providerProperties, FixtureProperties fixtureProperties) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
    }

    @GetMapping("/provider-mode")
    public Map<String, Object> providerMode() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("gateway", "llm-gateway");
        response.put("mode", providerProperties.getMode().name());
        response.put("externalCallsEnabled", providerProperties.getMode() == ExternalProviderMode.LIVE);
        if (providerProperties.getMode() == ExternalProviderMode.FIXTURE) {
            response.put("datasetId", fixtureProperties.getDatasetId());
            response.put("datasetVersion", fixtureProperties.getDatasetVersion());
            response.put("scenario", fixtureProperties.getScenario());
        }
        return response;
    }
}
