package com.jobseekercopilot.llmgateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.client.LlmProviderClient;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmGatewayServiceTest {

    @Mock
    private LlmProviderClient providerClient;

    private LlmGatewayService service;

    @BeforeEach
    void setUp() {
        service = new LlmGatewayService(providerClient);
    }

    @Test
    void mapsV2RequestToProviderNeutralCommandWithoutLeakingAdapterMetadata() throws Exception {
        when(providerClient.generate(any())).thenReturn(result("openai", "adapter-model"));

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
        assertEquals(7300L, response.usage().totalTokens());
    }

    @Test
    void mapsDeprecatedV1RequestThroughTheSameProviderBoundary() {
        when(providerClient.generate(any())).thenReturn(result("fixture", "fixture-model"));
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
        assertEquals("fixture-model", response.getModel());
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
                new GenerationUsage(4200, 3100, 7300),
                GenerationFinishReason.COMPLETED,
                adapter,
                model
        );
    }
}
