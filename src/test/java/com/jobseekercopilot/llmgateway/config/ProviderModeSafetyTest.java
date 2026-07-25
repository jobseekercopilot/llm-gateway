package com.jobseekercopilot.llmgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProviderModeSafetyTest {

    @Test
    void disabledIsTheSafeDefault() {
        assertDoesNotThrow(() -> safety(ExternalProviderMode.DISABLED, new MockEnvironment()).validate());
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
        configuration.setEndpoint("http://provider.invalid/v1");
        ProviderModeSafety insecureEndpoint = new ProviderModeSafety(
                properties, fixtureProperties(), configuration, new MockEnvironment());
        assertThrows(IllegalStateException.class, insecureEndpoint::validate);
    }

    @Test
    void validExplicitLiveConfigurationPassesOutsideAutomatedProfiles() {
        ExternalProviderProperties properties = new ExternalProviderProperties();
        properties.setMode(ExternalProviderMode.LIVE);
        ProviderModeSafety safety = new ProviderModeSafety(
                properties, fixtureProperties(), validLiveConfiguration(), new MockEnvironment());
        assertDoesNotThrow(safety::validate);
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
        return new ProviderModeSafety(properties, fixtureProperties(), new OpenAiConfiguration(), environment);
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
        configuration.setEndpoint("https://provider.example/v1/generate");
        configuration.setConnectTimeout(1000);
        configuration.setReadTimeout(1000);
        return configuration;
    }
}
