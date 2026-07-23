package com.jobseekercopilot.llmgateway.controller;

import com.jobseekercopilot.llmgateway.config.ExternalProviderMode;
import com.jobseekercopilot.llmgateway.config.ExternalProviderProperties;
import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal")
public class ProviderModeController {
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;

    public ProviderModeController(ExternalProviderProperties providerProperties, FixtureProperties fixtureProperties) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
    }

    @GetMapping("/provider-mode")
    public Map<String, Object> providerMode() {
        return Map.of(
                "gateway", "llm-gateway",
                "mode", providerProperties.getMode().name(),
                "datasetId", fixtureProperties.getDatasetId(),
                "datasetVersion", fixtureProperties.getDatasetVersion(),
                "scenario", fixtureProperties.getScenario(),
                "externalCallsEnabled", providerProperties.getMode() == ExternalProviderMode.LIVE);
    }
}
