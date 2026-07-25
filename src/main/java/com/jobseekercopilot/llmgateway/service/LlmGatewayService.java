package com.jobseekercopilot.llmgateway.service;

import com.jobseekercopilot.llmgateway.client.LlmProviderClient;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationRequest;
import com.jobseekercopilot.llmgateway.dto.GenerationResponse;
import com.jobseekercopilot.llmgateway.dto.LlmUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayService {
    static final int MAX_RESPONSE_CHARACTERS = 100_000;

    private final LlmProviderClient llmProviderClient;
    private final GenerationControls generationControls;

    public GenerationResponse generate(GenerationRequest request) {
        GenerationCommand command = new GenerationCommand(
                request.getTask(),
                request.getTrustedInstructions(),
                request.getUntrustedInput(),
                request.getOutput().getFormat(),
                request.getOutput().getSchemaId(),
                request.getOutput().getSchemaVersion(),
                request.getOutput().getJsonSchema(),
                request.getLimits().getMaxOutputTokens(),
                request.getLimits().getTemperature()
        );
        GenerationAdmission admission = generationControls.admit(command);
        ProviderGenerationResult result = execute(command, admission);
        return new GenerationResponse(
                "2.0",
                result.output(),
                result.finishReason(),
                result.usage(),
                generationControls.audit(result, admission),
                request.getOutput().getSchemaId(),
                request.getOutput().getSchemaVersion()
        );
    }

    public GenerateResponse generateLegacy(GenerateRequest request) {
        GenerationCommand command = new GenerationCommand(
                StringUtils.hasText(request.getTaskType()) ? request.getTaskType() : "GENERAL_GENERATION",
                request.getPrompt(),
                "No separately delimited source data was supplied through the deprecated v1 contract.",
                GenerationOutputFormat.TEXT,
                null,
                null,
                null,
                request.getMaxTokens(),
                request.getTemperature()
        );
        GenerationAdmission admission = generationControls.admit(command);
        ProviderGenerationResult result = execute(command, admission);
        String provider = result.adapterId().toUpperCase();
        return GenerateResponse.builder()
                .provider(provider)
                .model(result.modelId())
                .usage(LlmUsage.builder()
                        .provider(provider)
                        .model(result.modelId())
                        .inputTokens(result.usage().inputTokens())
                        .outputTokens(result.usage().outputTokens())
                        .totalTokens(result.usage().totalTokens())
                        .build())
                .response(result.output())
                .build();
    }

    private ProviderGenerationResult execute(
            GenerationCommand command,
            GenerationAdmission admission
    ) {
        long startedAt = System.nanoTime();
        log.info("generation request admitted task={} format={} temperature={} maxOutputTokens={} estimatedInputTokens={}",
                command.task(),
                command.outputFormat(),
                command.temperature(),
                command.maxOutputTokens(),
                admission.estimatedInputTokens());

        ProviderGenerationResult result = llmProviderClient.generate(command);
        if (result == null || !StringUtils.hasText(result.output())) {
            throw new GenerationBoundaryException("The provider returned no generated output.");
        }
        if (result.output().length() > MAX_RESPONSE_CHARACTERS) {
            throw new GenerationBoundaryException("The provider output exceeded the response character limit.");
        }
        if (result.usage() == null || result.finishReason() == null
                || !StringUtils.hasText(result.adapterId()) || !StringUtils.hasText(result.modelId())) {
            throw new GenerationBoundaryException("The provider returned incomplete generation metadata.");
        }
        if (result.usage().inputTokens() < 0
                || result.usage().outputTokens() < 0
                || result.usage().totalTokens()
                != result.usage().inputTokens() + result.usage().outputTokens()) {
            throw new GenerationBoundaryException("The provider returned invalid token usage.");
        }
        generationControls.validateActualUsage(command, result, admission);
        var audit = generationControls.audit(result, admission);

        log.info("generation request completed adapter={} model={} modelDeploymentVersion={} pricingVersion={} estimatedCostMicroUsd={} task={} finishReason={} inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                result.adapterId(),
                result.modelId(),
                audit.modelDeploymentVersion(),
                audit.pricingVersion(),
                audit.estimatedCostMicroUsd(),
                command.task(),
                result.finishReason(),
                result.usage().inputTokens(),
                result.usage().outputTokens(),
                result.usage().totalTokens(),
                (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }
}
