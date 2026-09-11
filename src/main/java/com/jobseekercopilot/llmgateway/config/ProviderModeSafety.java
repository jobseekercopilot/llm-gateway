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
    static final String REQUIRED_PRIVACY_POLICY_VERSION = "openai-api-data-controls-2026-08-23";
    static final long MAX_PRIVACY_REVIEW_DAYS = 93;
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final Logger log = LoggerFactory.getLogger(ProviderModeSafety.class);
    private final ExternalProviderProperties providerProperties;
    private final FixtureProperties fixtureProperties;
    private final OpenAiConfiguration openAiConfiguration;
    private final BedrockConfiguration bedrockConfiguration;
    private final Environment environment;
    private final Clock clock;

    @Autowired
    public ProviderModeSafety(
            ExternalProviderProperties providerProperties,
            FixtureProperties fixtureProperties,
            OpenAiConfiguration openAiConfiguration,
            BedrockConfiguration bedrockConfiguration,
            Environment environment
    ) {
        this(providerProperties, fixtureProperties, openAiConfiguration, bedrockConfiguration,
                environment, Clock.systemUTC());
    }

    ProviderModeSafety(
            ExternalProviderProperties providerProperties,
            FixtureProperties fixtureProperties,
            OpenAiConfiguration openAiConfiguration,
            BedrockConfiguration bedrockConfiguration,
            Environment environment,
            Clock clock
    ) {
        this.providerProperties = providerProperties;
        this.fixtureProperties = fixtureProperties;
        this.openAiConfiguration = openAiConfiguration;
        this.bedrockConfiguration = bedrockConfiguration;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
        ExternalProviderMode activeMode = providerProperties.getMode();
        boolean externalCallsEnabled = activeMode == ExternalProviderMode.LIVE
                || activeMode == ExternalProviderMode.BEDROCK;
        log.info("provider mode active gateway=llm-gateway mode={} externalCallsEnabled={}",
                activeMode, externalCallsEnabled);
        if (providerProperties.getMode() == ExternalProviderMode.FIXTURE) {
            log.info("fixture source active gateway=llm-gateway datasetId={} datasetVersion={} scenario={}",
                    fixtureProperties.getDatasetId(), fixtureProperties.getDatasetVersion(), fixtureProperties.getScenario());
        }
        if (activeMode == ExternalProviderMode.LIVE) {
            log.info("live provider privacy controls active gateway=llm-gateway region={} dataControl={} dataSharing={} policyVersion={}",
                    openAiConfiguration.getDataRegion(),
                    openAiConfiguration.getDataControlMode(),
                    openAiConfiguration.getDataSharingMode(),
                    openAiConfiguration.getPrivacyPolicyVersion());
        }
        if (activeMode == ExternalProviderMode.BEDROCK) {
            log.info("bedrock provider active gateway=llm-gateway awsRegion={} model={}",
                    bedrockConfiguration.getRegion(),
                    bedrockConfiguration.getModelId());
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
        if (automated && (mode == ExternalProviderMode.LIVE || mode == ExternalProviderMode.BEDROCK)) {
            throw new IllegalStateException(
                    "llm-gateway cannot start in a live provider mode with a test, e2e or fixture profile.");
        }
        if (mode == ExternalProviderMode.LIVE) {
            validateLiveConfiguration();
        }
        if (mode == ExternalProviderMode.BEDROCK) {
            validateBedrockConfiguration();
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
        if (openAiConfiguration.getCallTimeout() > 480_000) {
            throw new IllegalStateException("LIVE mode caps the provider-call deadline at 480 seconds.");
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

    private void validateBedrockConfiguration() {
        if (!StringUtils.hasText(bedrockConfiguration.getModelId())
                || bedrockConfiguration.getModelId().length() > 128
                || bedrockConfiguration.getModelId().chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("BEDROCK mode requires an explicit model or inference-profile id.");
        }
        if (!StringUtils.hasText(bedrockConfiguration.getRegion())
                || !bedrockConfiguration.getRegion().matches("[a-z]{2}-[a-z]+-\\d")) {
            throw new IllegalStateException("BEDROCK mode requires a valid AWS region, for example eu-west-2.");
        }
        if (StringUtils.hasText(bedrockConfiguration.getEndpointOverride())) {
            URI endpoint;
            try {
                endpoint = URI.create(bedrockConfiguration.getEndpointOverride());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException(
                        "BEDROCK mode requires a valid HTTPS endpoint override when one is set.", exception);
            }
            if (!"https".equalsIgnoreCase(endpoint.getScheme())
                    || !StringUtils.hasText(endpoint.getHost())
                    || StringUtils.hasText(endpoint.getUserInfo())) {
                throw new IllegalStateException(
                        "BEDROCK mode requires an HTTPS endpoint override with no embedded credentials.");
            }
        }
        if (bedrockConfiguration.getConnectTimeout() <= 0
                || bedrockConfiguration.getCallTimeout() <= 0
                || bedrockConfiguration.getConnectTimeout() > bedrockConfiguration.getCallTimeout()) {
            throw new IllegalStateException(
                    "BEDROCK mode requires positive timeouts and a connect timeout within the provider-call deadline.");
        }
        if (bedrockConfiguration.getCallTimeout() > 480_000) {
            throw new IllegalStateException("BEDROCK mode caps the provider-call deadline at 480 seconds.");
        }
        if (bedrockConfiguration.getMaxResponseBytes() < 1024
                || bedrockConfiguration.getMaxResponseBytes() > 2_097_152) {
            throw new IllegalStateException(
                    "BEDROCK mode requires a provider response limit between 1 KiB and 2 MiB.");
        }
        if (bedrockConfiguration.getMaxConcurrentCalls() < 1
                || bedrockConfiguration.getMaxConcurrentCalls() > 32) {
            throw new IllegalStateException(
                    "BEDROCK mode requires provider concurrency between 1 and 32.");
        }
        if (bedrockConfiguration.getCircuitFailureThreshold() < 1
                || bedrockConfiguration.getCircuitFailureThreshold() > 20
                || bedrockConfiguration.getCircuitOpenDuration() < 1_000
                || bedrockConfiguration.getCircuitOpenDuration() > 300_000) {
            throw new IllegalStateException(
                    "BEDROCK mode requires bounded provider circuit thresholds and recovery duration.");
        }
    }

    private void validateProviderIdentity() {
        validateOptionalIdentifier(
                "organization",
                openAiConfiguration.getOrganizationId(),
                "org-",
                "org_"
        );
        validateOptionalIdentifier("project", openAiConfiguration.getProjectId(), "proj_");
    }

    private void validateOptionalIdentifier(
            String name,
            String value,
            String... allowedPrefixes
    ) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        if (value.length() > 128
                || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(
                    "LIVE mode requires a valid optional OpenAI " + name + " ID."
            );
        }
        for (String prefix : allowedPrefixes) {
            if (value.startsWith(prefix)) {
                return;
            }
        }
        throw new IllegalStateException(
                "LIVE mode requires a valid optional OpenAI " + name + " ID."
        );
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

        LocalDate reviewedOn;
        LocalDate reviewDueOn;
        try {
            reviewedOn = LocalDate.parse(openAiConfiguration.getPrivacyReviewedOn());
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalStateException(
                    "LIVE mode requires an ISO-8601 completed OpenAI privacy review date.", exception);
        }
        try {
            reviewDueOn = LocalDate.parse(openAiConfiguration.getPrivacyReviewDueOn());
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalStateException(
                    "LIVE mode requires an ISO-8601 OpenAI privacy review due date.", exception);
        }
        LocalDate today = LocalDate.now(clock);
        if (reviewedOn.isAfter(today)) {
            throw new IllegalStateException("The completed OpenAI privacy review date cannot be in the future.");
        }
        if (reviewDueOn.isBefore(today)) {
            throw new IllegalStateException("The OpenAI privacy decision review is overdue.");
        }
        if (!reviewDueOn.isAfter(reviewedOn)
                || reviewDueOn.isAfter(reviewedOn.plusDays(MAX_PRIVACY_REVIEW_DAYS))) {
            throw new IllegalStateException(
                    "The OpenAI privacy review due date must follow the completed review and be within 93 days.");
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
