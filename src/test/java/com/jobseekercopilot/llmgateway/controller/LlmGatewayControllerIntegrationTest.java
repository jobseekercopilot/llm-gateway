package com.jobseekercopilot.llmgateway.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class LlmGatewayControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testSuccessfulGeneration() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setTaskType("CV_GENERATION");
        request.setPrompt("Generate a CV");
        request.setTemperature(0.5);
        request.setMaxTokens(2000);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider", is("MOCK")))
                .andExpect(jsonPath("$.model", is("mock-model")))
                .andExpect(jsonPath("$.usage.totalTokens", is(3000)))
                .andExpect(jsonPath("$.response", containsString("\"cv\"")));
    }

    @Test
    void testValidationFailureEmptyPrompt() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("");

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Validation Failed")));
    }

    @Test
    void testValidationFailureNullPrompt() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt(null);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Validation Failed")));
    }

    @Test
    void testValidationFailureInvalidTemperature() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt");
        request.setTemperature(3.0);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Validation Failed")));
    }

    @Test
    void testValidationFailureInvalidMaxTokens() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt");
        request.setMaxTokens(0);

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.error", is("Validation Failed")));
    }

    @Test
    void testGenerationWithDefaults() throws Exception {
        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt with defaults");

        mockMvc.perform(post("/api/v1/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider", is("MOCK")))
                .andExpect(jsonPath("$.model", is("mock-model")))
                .andExpect(jsonPath("$.usage.totalTokens", is(3000)))
                .andExpect(jsonPath("$.response", containsString("\"cv\"")));
    }
}
