package com.jobseekercopilot.llmgateway.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProviderModeSafety implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(ProviderModeSafety.class);
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;
    private final OpenAiConfiguration openAiConfiguration;
    private final Environment environment;

    public ProviderModeSafety(
            ExternalProviderProperties providerProperties,
            FixtureProperties fixtureProperties,
            OpenAiConfiguration openAiConfiguration,
            Environment environment
    ) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
        this.openAiConfiguration = openAiConfiguration;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
        log.info("provider mode active gateway=llm-gateway mode={} externalCallsEnabled={}",
                providerProperties.getMode(), providerProperties.getMode() == ExternalProviderMode.LIVE);
        if (providerProperties.getMode() == ExternalProviderMode.FIXTURE) {
            log.info("fixture source active gateway=llm-gateway datasetId={} datasetVersion={} scenario={}",
                    fixtureProperties.getDatasetId(), fixtureProperties.getDatasetVersion(), fixtureProperties.getScenario());
        }
    }

    void validate() {
        ExternalProviderMode mode = providerProperties.getMode();
        if (mode == null) {
            throw new IllegalStateException("external-provider.mode must be DISABLED, FIXTURE or LIVE.");
        }
        if (StringUtils.hasText(environment.getProperty("llm.mock-mode"))) {
            throw new IllegalStateException(
                    "llm.mock-mode is obsolete; select exactly one external-provider.mode instead.");
        }

        boolean production = environment.acceptsProfiles(Profiles.of("prod", "production"));
        boolean automated = environment.acceptsProfiles(Profiles.of("test", "e2e", "fixture"));
        if (production && mode == ExternalProviderMode.FIXTURE) {
            throw new IllegalStateException("llm-gateway cannot start in FIXTURE mode with a production profile.");
        }
        if (automated && mode == ExternalProviderMode.LIVE) {
            throw new IllegalStateException("llm-gateway cannot start in LIVE mode with a test, e2e or fixture profile.");
        }
        if (mode == ExternalProviderMode.LIVE) {
            validateLiveConfiguration();
        }
        if (mode == ExternalProviderMode.FIXTURE) {
            validateFixtureConfiguration();
        }
    }

    private void validateLiveConfiguration() {
        if (!StringUtils.hasText(openAiConfiguration.getApiKey())
                || openAiConfiguration.getApiKey().trim().length() < 20) {
            throw new IllegalStateException("LIVE mode requires a non-empty runtime provider credential.");
        }
        if (!StringUtils.hasText(openAiConfiguration.getModel())) {
            throw new IllegalStateException("LIVE mode requires an explicit model.");
        }
        if (openAiConfiguration.getConnectTimeout() <= 0 || openAiConfiguration.getReadTimeout() <= 0) {
            throw new IllegalStateException("LIVE mode requires positive connect and read timeouts.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(openAiConfiguration.getEndpoint());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LIVE mode requires a valid HTTPS provider endpoint.", exception);
        }
        if (!"https".equalsIgnoreCase(endpoint.getScheme()) || !StringUtils.hasText(endpoint.getHost())) {
            throw new IllegalStateException("LIVE mode requires a valid HTTPS provider endpoint.");
        }
    }

    private void validateFixtureConfiguration() {
        if (!StringUtils.hasText(fixtureProperties.getSystemDataServiceUrl())
                || !StringUtils.hasText(fixtureProperties.getDatasetId())
                || !StringUtils.hasText(fixtureProperties.getDatasetVersion())
                || !StringUtils.hasText(fixtureProperties.getScenario())) {
            throw new IllegalStateException("FIXTURE mode requires a URL, dataset ID, dataset version and scenario.");
        }
    }
}
