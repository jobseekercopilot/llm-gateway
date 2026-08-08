package com.jobseekercopilot.llmgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderModeSafetyTest {
    private static final Clock REVIEW_CLOCK =
            Clock.fixed(Instant.parse("2026-07-25T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void disabledIsTheSafeDefault() {
        assertDoesNotThrow(() -> safety(ExternalProviderMode.DISABLED, new MockEnvironment()).validate());
    }

    @Test
    void defaultProviderDeadlineUsesTheBoundedMaximum() {
        assertEquals(480_000, new OpenAiConfiguration().getCallTimeout());
    }

    @Test
    void productionCannotUseFixtureMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("production");
        assertThrows(IllegalStateException.class,
                () -> safety(ExternalProviderMode.FIXTURE, environment).validate());
    }

    @Test
    void automatedProfilesCannotUseLiveMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        assertThrows(IllegalStateException.class,
                () -> safety(ExternalProviderMode.LIVE, environment).validate());
    }

    @Test
    void liveRequiresCredentialAndHttpsEndpoint() {
        ProviderModeSafety missingCredential = safety(ExternalProviderMode.LIVE, new MockEnvironment());
        assertThrows(IllegalStateException.class, missingCredential::validate);

        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.LIVE);
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setEndpoint("http://api.openai.com/v1/chat/completions");
        ProviderModeSafety insecureEndpoint = new ProviderModeSafety(
                properties, fixtureProperties(), configuration, new MockEnvironment(), REVIEW_CLOCK);
        assertThrows(IllegalStateException.class, insecureEndpoint::validate);
    }

    @Test
    void validExplicitLiveConfigurationPassesOutsideAutomatedProfiles() {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.LIVE);
        ProviderModeSafety safety = new ProviderModeSafety(
                properties, fixtureProperties(), validLiveConfiguration(), new MockEnvironment(), REVIEW_CLOCK);
        assertDoesNotThrow(safety::validate);
    }

    @Test
    void liveAllowsProjectScopedCredentialsWithoutIdentityHeaders() {
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setOrganizationId("");
        configuration.setProjectId("");

        assertDoesNotThrow(() -> liveSafety(configuration).validate());
    }

    @Test
    void liveRejectsMalformedOptionalIdentityHeaders() {
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setOrganizationId("not-an-organization-id");

        assertThrows(
                IllegalStateException.class,
                () -> liveSafety(configuration).validate()
        );
    }

    @Test
    void liveRejectsMissingOrStalePrivacyDecision() {
        OpenAiConfiguration missingDecision = validLiveConfiguration();
        missingDecision.setPrivacyDecisionId("");
        assertThrows(IllegalStateException.class, () -> liveSafety(missingDecision).validate());

        OpenAiConfiguration stalePolicy = validLiveConfiguration();
        stalePolicy.setPrivacyPolicyVersion("openai-api-data-controls-2025-01-01");
        assertThrows(IllegalStateException.class, () -> liveSafety(stalePolicy).validate());

        OpenAiConfiguration expiredReview = validLiveConfiguration();
        expiredReview.setPrivacyReviewOn("2026-07-24");
        assertThrows(IllegalStateException.class, () -> liveSafety(expiredReview).validate());

        OpenAiConfiguration distantReview = validLiveConfiguration();
        distantReview.setPrivacyReviewOn("2027-07-25");
        assertThrows(IllegalStateException.class, () -> liveSafety(distantReview).validate());
    }

    @Test
    void liveRequiresDataSharingToBeDisabled() {
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setDataSharingMode(null);
        assertThrows(IllegalStateException.class, () -> liveSafety(configuration).validate());
    }

    @Test
    void livePinsTheDeclaredRegionToItsExactEndpoint() {
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setDataRegion(OpenAiDataRegion.UNITED_KINGDOM);
        configuration.setDataControlMode(OpenAiDataControlMode.ZERO_DATA_RETENTION);
        configuration.setEndpoint("https://api.openai.com/v1/chat/completions");
        assertThrows(IllegalStateException.class, () -> liveSafety(configuration).validate());

        configuration.setEndpoint("https://gb.api.openai.com/v1/chat/completions");
        assertDoesNotThrow(() -> liveSafety(configuration).validate());
    }

    @Test
    void nonUsRegionalDataResidencyRequiresEnhancedDataControls() {
        OpenAiConfiguration configuration = validLiveConfiguration();
        configuration.setDataRegion(OpenAiDataRegion.EUROPE);
        configuration.setEndpoint("https://eu.api.openai.com/v1/chat/completions");
        assertThrows(IllegalStateException.class, () -> liveSafety(configuration).validate());

        configuration.setDataControlMode(OpenAiDataControlMode.MODIFIED_ABUSE_MONITORING);
        assertDoesNotThrow(() -> liveSafety(configuration).validate());
    }

    @Test
    void liveFailsClosedOnUnboundedResilienceConfiguration() {
        OpenAiConfiguration excessiveDeadline = validLiveConfiguration();
        excessiveDeadline.setCallTimeout(480_001);
        assertThrows(IllegalStateException.class, () -> liveSafety(excessiveDeadline).validate());

        OpenAiConfiguration responseTooLarge = validLiveConfiguration();
        responseTooLarge.setMaxResponseBytes(2_097_153);
        assertThrows(IllegalStateException.class, () -> liveSafety(responseTooLarge).validate());

        OpenAiConfiguration excessiveConcurrency = validLiveConfiguration();
        excessiveConcurrency.setMaxConcurrentCalls(33);
        assertThrows(IllegalStateException.class, () -> liveSafety(excessiveConcurrency).validate());

        OpenAiConfiguration invalidCircuit = validLiveConfiguration();
        invalidCircuit.setCircuitFailureThreshold(0);
        assertThrows(IllegalStateException.class, () -> liveSafety(invalidCircuit).validate());
    }

    @Test
    void obsoleteMockSettingFailsEvenWhenFalse() {
        MockEnvironment environment = new MockEnvironment().withProperty("llm.mock-mode", "false");
        assertThrows(IllegalStateException.class,
                () -> safety(ExternalProviderMode.DISABLED, environment).validate());
    }

    private ProviderModeSafety safety(ExternalProviderMode mode, MockEnvironment environment) {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(mode);
        return new ProviderModeSafety(
                properties, fixtureProperties(), new OpenAiConfiguration(), environment, REVIEW_CLOCK);
    }

    private ProviderModeSafety liveSafety(OpenAiConfiguration configuration) {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.LIVE);
        return new ProviderModeSafety(
                properties, fixtureProperties(), configuration, new MockEnvironment(), REVIEW_CLOCK);
    }

    private FixtureProperties fixtureProperties() {
        FixtureProperties properties = new FixtureProperties();
        properties.setSystemDataServiceUrl("http://localhost:8103");
        properties.setDatasetId("test");
        properties.setDatasetVersion("1.0.0");
        properties.setScenario("safe");
        return properties;
    }

    private OpenAiConfiguration validLiveConfiguration() {
        OpenAiConfiguration configuration = new OpenAiConfiguration();
        configuration.setApiKey("runtime-secret-with-enough-characters");
        configuration.setModel("configured-model");
        configuration.setEndpoint("https://api.openai.com/v1/chat/completions");
        configuration.setOrganizationId("org-jobseeker-copilot");
        configuration.setProjectId("proj_jobseeker_copilot_beta");
        configuration.setDataRegion(OpenAiDataRegion.GLOBAL);
        configuration.setDataControlMode(OpenAiDataControlMode.STANDARD_30_DAY_ABUSE_MONITORING);
        configuration.setDataSharingMode(OpenAiDataSharingMode.DISABLED);
        configuration.setPrivacyPolicyVersion(ProviderModeSafety.REQUIRED_PRIVACY_POLICY_VERSION);
        configuration.setPrivacyDecisionId("privacy-decision/llm-02");
        configuration.setPrivacyOwner("Named privacy owner");
        configuration.setPrivacyReviewOn("2026-10-25");
        configuration.setConnectTimeout(1000);
        configuration.setCallTimeout(1000);
        return configuration;
    }
}
