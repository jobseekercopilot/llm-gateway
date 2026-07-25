package com.jobseekercopilot.llmgateway.config;

import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class GenerationControlSafety implements ApplicationRunner {
    static final long MAX_RATE_MICRO_USD_PER_MILLION_TOKENS = 1_000_000_000_000L;
    private static final int PROVIDER_MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_INPUT_TOKEN_RESERVE = 4096;
    private static final Pattern VERSION_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,127}");
    private static final Pattern TASK_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");

    private final GenerationControlProperties controls;
    private final ExternalProviderProperties providerProperties;
    private final OpenAiConfiguration openAiConfiguration;

    public GenerationControlSafety(
            GenerationControlProperties controls,
            ExternalProviderProperties providerProperties,
            OpenAiConfiguration openAiConfiguration
    ) {
        this.controls = controls;
        this.providerProperties = providerProperties;
        this.openAiConfiguration = openAiConfiguration;
    }

    @Override
    public void run(ApplicationArguments args) {
        validate();
    }

    void validate() {
        validateVersion("admission policy", controls.getAdmissionPolicyVersion());
        validateModelId();
        validateVersion("model deployment", controls.getModelDeploymentVersion());
        validateVersion("pricing", controls.getPricingVersion());

        if (controls.getInputTokenReserve() < 0
                || controls.getInputTokenReserve() > MAX_INPUT_TOKEN_RESERVE) {
            throw new IllegalStateException(
                    "generation-controls.input-token-reserve must be between 0 and 4096.");
        }
        validateRate("input", controls.getInputRateMicroUsdPerMillionTokens());
        validateRate("output", controls.getOutputRateMicroUsdPerMillionTokens());

        if (controls.getTasks() == null || controls.getTasks().isEmpty()) {
            throw new IllegalStateException("generation-controls.tasks must define at least one supported task.");
        }
        for (Map.Entry<String, GenerationControlProperties.TaskLimit> entry
                : controls.getTasks().entrySet()) {
            String task = entry.getKey();
            GenerationControlProperties.TaskLimit limit = entry.getValue();
            if (!StringUtils.hasText(task) || !TASK_PATTERN.matcher(task).matches() || limit == null) {
                throw new IllegalStateException("generation-controls.tasks contains an invalid task policy.");
            }
            if (limit.getMaxEstimatedInputTokens() < 1) {
                throw new IllegalStateException(
                        "Every generation task requires a positive estimated input-token ceiling.");
            }
            if (limit.getMaxOutputTokens() < 1
                    || limit.getMaxOutputTokens() > PROVIDER_MAX_OUTPUT_TOKENS) {
                throw new IllegalStateException(
                        "Every generation task requires an output-token ceiling between 1 and 4096.");
            }
        }

        if (providerProperties.getMode() == ExternalProviderMode.LIVE) {
            if (controls.getModelId().startsWith("fixture-")
                    || !controls.getModelId().equals(openAiConfiguration.getModel())
                    || controls.getModelDeploymentVersion().startsWith("fixture-")
                    || controls.getPricingVersion().startsWith("non-billable-")
                    || controls.getInputRateMicroUsdPerMillionTokens() <= 0
                    || controls.getOutputRateMicroUsdPerMillionTokens() <= 0) {
                throw new IllegalStateException(
                        "LIVE mode requires reviewed model deployment and non-zero versioned pricing controls.");
            }
        }
    }

    private void validateModelId() {
        String modelId = controls.getModelId();
        if (!StringUtils.hasText(modelId)
                || modelId.length() > 128
                || modelId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(
                    "generation-controls requires an explicit model ID.");
        }
    }

    private void validateVersion(String name, String value) {
        if (!StringUtils.hasText(value) || !VERSION_PATTERN.matcher(value).matches()) {
            throw new IllegalStateException(
                    "generation-controls requires a stable " + name + " version.");
        }
    }

    private void validateRate(String name, long value) {
        if (value < 0 || value > MAX_RATE_MICRO_USD_PER_MILLION_TOKENS) {
            throw new IllegalStateException(
                    "generation-controls " + name + " token rate is outside the supported range.");
        }
    }
}
