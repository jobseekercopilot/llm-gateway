package com.jobseekercopilot.llmgateway.config;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ProviderModeSafety implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProviderModeSafety.class);
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;
    private final Environment environment;

    public ProviderModeSafety(ExternalProviderProperties providerProperties, FixtureProperties fixtureProperties, Environment environment) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        if (production && providerProperties.getMode() == ExternalProviderMode.FIXTURE) {
            throw new IllegalStateException("llm-gateway cannot start in FIXTURE mode with a production profile.");
        }
        log.info("provider mode active gateway=llm-gateway mode={} datasetId={} datasetVersion={} scenario={} externalCallsEnabled={}",
                providerProperties.getMode(), fixtureProperties.getDatasetId(), fixtureProperties.getDatasetVersion(),
                fixtureProperties.getScenario(), providerProperties.getMode() == ExternalProviderMode.LIVE);
    }
}
