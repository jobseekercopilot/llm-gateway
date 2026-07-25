package com.jobseekercopilot.llmgateway.config;

import java.net.URI;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProviderModeSafety implements ApplicationRunner {
    static final String REQUIRED_PRIVACY_POLICY_VERSION = "openai-api-data-controls-2026-07-25";
    static final long MAX_PRIVACY_REVIEW_DAYS = 93;
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Logger log = LoggerFactory.getLogger(ProviderModeSafety.class);
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;
    private final OpenAiConfiguration openAiConfiguration;
    private final Environment environment;
    private final Clock clock;

    @Autowired
    public ProviderModeSafety(
            ExternalProviderProperties providerProperties,
            FixtureProperties fixtureProperties,
            OpenAiConfiguration openAiConfiguration,
            Environment environment
    ) {
        this(providerProperties, fixtureProperties, openAiConfiguration, environment, Clock.systemUTC());
    }

    ProviderModeSafety(
            ExternalProviderProperties providerProperties,
            FixtureProperties fixtureProperties,
            OpenAiConfiguration openAiConfiguration,
            Environment environment,
            Clock clock
    ) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
        this.openAiConfiguration = openAiConfiguration;
        this.environment = environment;
        this.clock = clock;
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
        if (providerProperties.getMode() == ExternalProviderMode.LIVE) {
            log.info("live provider privacy controls active gateway=llm-gateway region={} dataControl={} dataSharing={} policyVersion={}",
                    openAiConfiguration.getDataRegion(),
                    openAiConfiguration.getDataControlMode(),
                    openAiConfiguration.getDataSharingMode(),
                    openAiConfiguration.getPrivacyPolicyVersion());
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
        validateProviderIdentity();
        validatePrivacyDecision();
        if (openAiConfiguration.getConnectTimeout() <= 0
                || openAiConfiguration.getCallTimeout() <= 0
                || openAiConfiguration.getConnectTimeout() > openAiConfiguration.getCallTimeout()) {
            throw new IllegalStateException(
                    "LIVE mode requires positive timeouts and a connect timeout within the provider-call deadline.");
        }
        if (openAiConfiguration.getCallTimeout() > 120_000) {
            throw new IllegalStateException("LIVE mode caps the provider-call deadline at 120 seconds.");
        }
        if (openAiConfiguration.getMaxResponseBytes() < 1024
                || openAiConfiguration.getMaxResponseBytes() > 2_097_152) {
            throw new IllegalStateException(
                    "LIVE mode requires a provider response limit between 1 KiB and 2 MiB.");
        }
        if (openAiConfiguration.getMaxConcurrentCalls() < 1
                || openAiConfiguration.getMaxConcurrentCalls() > 32) {
            throw new IllegalStateException(
                    "LIVE mode requires provider concurrency between 1 and 32.");
        }
        if (openAiConfiguration.getCircuitFailureThreshold() < 1
                || openAiConfiguration.getCircuitFailureThreshold() > 20
                || openAiConfiguration.getCircuitOpenDuration() < 1_000
                || openAiConfiguration.getCircuitOpenDuration() > 300_000) {
            throw new IllegalStateException(
                    "LIVE mode requires bounded provider circuit thresholds and recovery duration.");
        }
        URI endpoint;
        try {
            endpoint = URI.create(openAiConfiguration.getEndpoint());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("LIVE mode requires a valid HTTPS provider endpoint.", exception);
        }
        OpenAiDataRegion dataRegion = openAiConfiguration.getDataRegion();
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !dataRegion.endpointHost().equalsIgnoreCase(endpoint.getHost())
                || endpoint.getPort() != -1
                || !CHAT_COMPLETIONS_PATH.equals(endpoint.getPath())
                || StringUtils.hasText(endpoint.getQuery())
                || StringUtils.hasText(endpoint.getFragment())
                || StringUtils.hasText(endpoint.getUserInfo())) {
            throw new IllegalStateException(
                    "LIVE mode requires the declared OpenAI region's exact HTTPS Chat Completions endpoint.");
        }
        if (dataRegion.enhancedDataControlsRequired()
                && openAiConfiguration.getDataControlMode()
                == OpenAiDataControlMode.STANDARD_30_DAY_ABUSE_MONITORING) {
            throw new IllegalStateException(
                    "The declared OpenAI region requires Modified Abuse Monitoring or Zero Data Retention.");
        }
    }

    private void validateProviderIdentity() {
        validateIdentifier("organization", openAiConfiguration.getOrganizationId(), "org-", "org_");
        validateIdentifier("project", openAiConfiguration.getProjectId(), "proj_");
    }

    private void validateIdentifier(String name, String value, String... allowedPrefixes) {
        if (!StringUtils.hasText(value)
                || value.length() > 128
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("LIVE mode requires an explicit OpenAI " + name + " ID.");
        }
        for (String prefix : allowedPrefixes) {
            if (value.startsWith(prefix)) {
                return;
            }
        }
        throw new IllegalStateException("LIVE mode requires a valid OpenAI " + name + " ID.");
    }

    private void validatePrivacyDecision() {
        if (openAiConfiguration.getDataRegion() == null) {
            throw new IllegalStateException("LIVE mode requires an explicit OpenAI data region.");
        }
        if (openAiConfiguration.getDataControlMode() == null) {
            throw new IllegalStateException("LIVE mode requires an explicit OpenAI data-control mode.");
        }
        if (openAiConfiguration.getDataSharingMode() != OpenAiDataSharingMode.DISABLED) {
            throw new IllegalStateException("LIVE mode requires OpenAI API data sharing to be declared DISABLED.");
        }
        if (!REQUIRED_PRIVACY_POLICY_VERSION.equals(openAiConfiguration.getPrivacyPolicyVersion())) {
            throw new IllegalStateException(
                    "LIVE mode requires the current reviewed OpenAI privacy policy version.");
        }
        if (!StringUtils.hasText(openAiConfiguration.getPrivacyDecisionId())
                || openAiConfiguration.getPrivacyDecisionId().length() > 200
                || openAiConfiguration.getPrivacyDecisionId().chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("LIVE mode requires a durable privacy decision reference.");
        }
        if (!StringUtils.hasText(openAiConfiguration.getPrivacyOwner())
                || openAiConfiguration.getPrivacyOwner().trim().length() < 3
                || openAiConfiguration.getPrivacyOwner().length() > 120
                || openAiConfiguration.getPrivacyOwner().contains("\n")
                || openAiConfiguration.getPrivacyOwner().contains("\r")) {
            throw new IllegalStateException("LIVE mode requires a named privacy decision owner.");
        }

        LocalDate reviewOn;
        try {
            reviewOn = LocalDate.parse(openAiConfiguration.getPrivacyReviewOn());
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalStateException(
                    "LIVE mode requires an ISO-8601 OpenAI privacy review date.", exception);
        }
        LocalDate today = LocalDate.now(clock);
        if (reviewOn.isBefore(today)) {
            throw new IllegalStateException("The OpenAI privacy decision review date has expired.");
        }
        if (reviewOn.isAfter(today.plusDays(MAX_PRIVACY_REVIEW_DAYS))) {
            throw new IllegalStateException(
                    "The OpenAI privacy decision review date must be within 93 days.");
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
