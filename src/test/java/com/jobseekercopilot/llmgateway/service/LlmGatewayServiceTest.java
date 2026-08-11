package com.jobseekercopilot.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.client.LlmProviderClient;
import com.jobseekercopilot.llmgateway.config.GenerationControlProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.dto.GenerationLimits;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputContract;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationRequest;
import com.jobseekercopilot.llmgateway.dto.GenerationResponse;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationLimitException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayServiceTest {

    @Mock
    private LlmProviderClient providerClient;

    private LlmGatewayService service;

    @BeforeEach
    void setUp() {
        service = new LlmGatewayService(providerClient, new GenerationControls(controls()));
    }

    @Test
    void mapsV2RequestToProviderNeutralCommandWithoutLeakingAdapterMetadata() throws Exception {
        when(providerClient.generate(any())).thenReturn(result("openai", "test-model"));

        GenerationResponse response = service.generate(jsonSchemaRequest());

        ArgumentCaptor<GenerationCommand> command = ArgumentCaptor.forClass(GenerationCommand.class);
        verify(providerClient).generate(command.capture());
        assertEquals("DOCUMENT_DRAFT", command.getValue().task());
        assertEquals("Use approved facts only.", command.getValue().trustedInstructions());
        assertEquals("{\"job\":\"untrusted\"}", command.getValue().untrustedInput());
        assertEquals(GenerationOutputFormat.JSON_SCHEMA, command.getValue().outputFormat());
        assertEquals("document-output", command.getValue().schemaId());
        assertEquals(3000, command.getValue().maxOutputTokens());
        assertEquals("2.0", response.contractVersion());
        assertEquals("{\"document\":\"ok\"}", response.output());
        assertEquals("document-output", response.schemaId());
        assertEquals(7100L, response.usage().totalTokens());
        assertEquals("test-model", response.audit().modelId());
        assertEquals("test-deployment-1", response.audit().modelDeploymentVersion());
        assertEquals("test-pricing-1", response.audit().pricingVersion());
        assertEquals(39_500L, response.audit().estimatedCostMicroUsd());
    }

    @Test
    void mapsDeprecatedV1RequestThroughTheSameProviderBoundary() {
        when(providerClient.generate(any())).thenReturn(result("fixture", "test-model"));
        GenerateRequest request = new GenerateRequest();
        request.setTaskType("DOCUMENT_DRAFT");
        request.setPrompt("Legacy combined prompt");
        request.setTemperature(0.3);
        request.setMaxTokens(3000);

        GenerateResponse response = service.generateLegacy(request);

        ArgumentCaptor<GenerationCommand> command = ArgumentCaptor.forClass(GenerationCommand.class);
        verify(providerClient).generate(command.capture());
        assertEquals("Legacy combined prompt", command.getValue().trustedInstructions());
        assertEquals(GenerationOutputFormat.TEXT, command.getValue().outputFormat());
        assertEquals("FIXTURE", response.getProvider());
        assertEquals("test-model", response.getModel());
    }

    @Test
    void rejectsOversizedProviderOutput() {
        when(providerClient.generate(any())).thenReturn(new ProviderGenerationResult(
                "x".repeat(LlmGatewayService.MAX_RESPONSE_CHARACTERS + 1),
                new GenerationUsage(1, 1, 2),
                GenerationFinishReason.COMPLETED,
                "fixture",
                "fixture-model"
        ));

        assertThrows(GenerationBoundaryException.class, () -> service.generate(textRequest()));
    }

    @Test
    void rejectsIncompleteProviderMetadata() {
        when(providerClient.generate(any())).thenReturn(new ProviderGenerationResult(
                "content", null, GenerationFinishReason.COMPLETED, "fixture", "fixture-model"));

        assertThrows(GenerationBoundaryException.class, () -> service.generate(textRequest()));
    }

    @Test
    void rejectsUnsupportedTaskBeforeCallingProvider() {
        GenerationRequest request = textRequest();
        request.setTask("UNSUPPORTED_TASK");

        assertThrows(GenerationLimitException.class, () -> service.generate(request));
        verifyNoInteractions(providerClient);
    }

    @Test
    void rejectsTaskOutputCeilingBeforeCallingProvider() {
        GenerationRequest request = textRequest();
        request.setLimits(new GenerationLimits(3001, 0.3));

        assertThrows(GenerationLimitException.class, () -> service.generate(request));
        verifyNoInteractions(providerClient);
    }

    @Test
    void rejectsProviderUsageBeyondTheAdmittedOutputLimit() {
        when(providerClient.generate(any())).thenReturn(new ProviderGenerationResult(
                "content",
                new GenerationUsage(10, 3001, 3011),
                GenerationFinishReason.COMPLETED,
                "fixture",
                "fixture-model"
        ));

        assertThrows(GenerationBoundaryException.class, () -> service.generate(textRequest()));
    }

    @Test
    void retriesOneExplicitRateLimitThenReturnsAttemptAudit() {
        when(providerClient.generate(any()))
                .thenThrow(new ProviderFailureException(
                        ProviderFailureType.RATE_LIMITED,
                        "Provider refused before generation.",
                        0L))
                .thenReturn(result("openai", "test-model"));

        GenerationResponse response = service.generate(textRequest());

        verify(providerClient, times(2)).generate(any());
        assertEquals(2, response.audit().providerAttemptCount());
        assertEquals(1, response.audit().automaticRetryCount());
        assertEquals("RATE_LIMITED", response.audit().retryReason());
    }

    @Test
    void neverRetriesAmbiguousProviderTransportFailure() {
        when(providerClient.generate(any()))
                .thenThrow(new ProviderFailureException(
                        ProviderFailureType.UNAVAILABLE,
                        "Provider transport failed.",
                        null));

        assertThrows(
                ProviderFailureException.class,
                () -> service.generate(textRequest()));

        verify(providerClient, times(1)).generate(any());
    }

    private GenerationRequest jsonSchemaRequest() throws Exception {
        return new GenerationRequest(
                "2.0",
                "DOCUMENT_DRAFT",
                "Use approved facts only.",
                "{\"job\":\"untrusted\"}",
                new GenerationOutputContract(
                        GenerationOutputFormat.JSON_SCHEMA,
                        "document-output",
                        "1.0",
                        new ObjectMapper().readTree("""
                                {"type":"object","additionalProperties":false}
                                """)
                ),
                new GenerationLimits(3000, 0.3)
        );
    }

    private GenerationRequest textRequest() {
        return new GenerationRequest(
                "2.0",
                "DOCUMENT_DRAFT",
                "Use approved facts only.",
                "Untrusted source data",
                new GenerationOutputContract(GenerationOutputFormat.TEXT, null, null, null),
                new GenerationLimits(3000, 0.3)
        );
    }

    private ProviderGenerationResult result(String adapter, String model) {
        return new ProviderGenerationResult(
                "{\"document\":\"ok\"}",
                new GenerationUsage(4200, 2900, 7100),
                GenerationFinishReason.COMPLETED,
                adapter,
                model
        );
    }

    private GenerationControlProperties controls() {
        GenerationControlProperties controls = new GenerationControlProperties();
        controls.setAdmissionPolicyVersion("test-admission-1");
        controls.setModelId("test-model");
        controls.setModelDeploymentVersion("test-deployment-1");
        controls.setPricingVersion("test-pricing-1");
        controls.setInputRateMicroUsdPerMillionTokens(2_500_000);
        controls.setOutputRateMicroUsdPerMillionTokens(10_000_000);
        controls.setInputTokenReserve(256);
        controls.setProviderRetryDelayMillis(0);
        GenerationControlProperties.TaskLimit taskLimit =
                new GenerationControlProperties.TaskLimit();
        taskLimit.setMaxEstimatedInputTokens(60_000);
        taskLimit.setMaxOutputTokens(3000);
        controls.setTasks(Map.of("DOCUMENT_DRAFT", taskLimit));
        return controls;
    }
}
