package com.jobseekercopilot.llmgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.generated.systemdataservice.api.FixtureControllerApi;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmResponse;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerationLimits;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputContract;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.dto.GenerationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LlmGatewayControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private FixtureControllerApi fixtureControllerApi;

    @BeforeEach
    void fixtureResponse() {
        when(fixtureControllerApi.llm(any())).thenReturn(new FixtureLlmResponse()
                .provider("fixture")
                .model("fixture-model")
                .response("{\"document\":\"deterministic\"}")
                .inputTokens(12L)
                .outputTokens(8L)
                .totalTokens(20L));
    }

    @Test
    void generatesThroughProviderNeutralV2Contract() throws Exception {
        mockMvc.perform(post("/api/v2/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(textRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion", is("2.0")))
                .andExpect(jsonPath("$.output", containsString("deterministic")))
                .andExpect(jsonPath("$.finishReason", is("COMPLETED")))
                .andExpect(jsonPath("$.usage.totalTokens", is(20)))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.model").doesNotExist());

        ArgumentCaptor<com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmRequest> payload =
                ArgumentCaptor.forClass(
                        com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmRequest.class);
        verify(fixtureControllerApi).llm(payload.capture());
        assertEquals("DOCUMENT_DRAFT", payload.getValue().getOperation());
    }

    @Test
    void keepsDeprecatedV1EndpointWorkingDuringConsumerMigration() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setTaskType("DOCUMENT_DRAFT");
        request.setPrompt("Legacy prompt");
        request.setTemperature(0.3);
        request.setMaxTokens(3000);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider", is("FIXTURE")))
                .andExpect(jsonPath("$.model", is("fixture-model")))
                .andExpect(jsonPath("$.response", containsString("deterministic")));
    }

    @Test
    void rejectsMissingSchemaForJsonSchemaFormat() throws Exception {
        GenerationRequest request = textRequest();
        request.setOutput(new GenerationOutputContract(GenerationOutputFormat.JSON_SCHEMA, null, null, null));

        mockMvc.perform(post("/api/v2/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("VALIDATION_FAILED")));
    }

    @Test
    void rejectsPromptAndTokenLimitsBeforeAdapterUse() throws Exception {
        GenerationRequest request = textRequest();
        request.setTrustedInstructions("x".repeat(12_001));
        request.setLimits(new GenerationLimits(4097, 1.1));

        mockMvc.perform(post("/api/v2/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void rejectsUnknownFields() throws Exception {
        String request = objectMapper.writeValueAsString(textRequest());
        request = request.substring(0, request.length() - 1) + ",\"provider\":\"OPENAI\"}";

        mockMvc.perform(post("/api/v2/generations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("INVALID_REQUEST")));
    }

    private GenerationRequest textRequest() {
        return new GenerationRequest(
                "2.0",
                "DOCUMENT_DRAFT",
                "Use approved facts only.",
                "{\"job\":\"untrusted\"}",
                new GenerationOutputContract(GenerationOutputFormat.TEXT, null, null, null),
                new GenerationLimits(3000, 0.3)
        );
    }
}
