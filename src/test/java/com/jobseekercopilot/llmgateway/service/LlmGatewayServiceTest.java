package com.jobseekercopilot.llmgateway.service;

import com.jobseekercopilot.llmgateway.client.OpenAiClient;
import com.jobseekercopilot.llmgateway.client.OpenAiGenerationResult;
import com.jobseekercopilot.llmgateway.config.LlmConfiguration;
import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.dto.LlmUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LlmGatewayServiceTest {

    @Mock
    private OpenAiClient openAiClient;

    @Mock
    private LlmConfiguration llmConfiguration;

    @Mock
    private OpenAiConfiguration openAiConfiguration;

    private LlmGatewayService llmGatewayService;

    @BeforeEach
    void setUp() {
        llmGatewayService = new LlmGatewayService(openAiClient, llmConfiguration, openAiConfiguration);
    }

    @Test
    void testSuccessfulGeneration() {
        when(llmConfiguration.isMockMode()).thenReturn(false);
        when(openAiConfiguration.getModel()).thenReturn("gpt-4.1-mini");
        when(openAiClient.generate(any(GenerateRequest.class)))
                .thenReturn(new OpenAiGenerationResult("Generated content", usage()));

        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt");
        request.setTemperature(0.5);
        request.setMaxTokens(1000);

        GenerateResponse response = llmGatewayService.generate(request);

        assertNotNull(response);
        assertEquals("OPENAI", response.getProvider());
        assertEquals("gpt-4.1-mini", response.getModel());
        assertEquals("Generated content", response.getResponse());
        assertEquals(7300L, response.getUsage().getTotalTokens());

        verify(llmConfiguration).isMockMode();
        verify(openAiClient).generate(any(GenerateRequest.class));
    }

    @Test
    void testMockModeGeneration() {
        when(llmConfiguration.isMockMode()).thenReturn(true);

        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt");

        GenerateResponse response = llmGatewayService.generate(request);

        assertNotNull(response);
        assertEquals("MOCK", response.getProvider());
        assertEquals("mock-model", response.getModel());
        assertEquals(3000L, response.getUsage().getTotalTokens());
        assertTrue(response.getResponse().contains("\"cv\""));

        verify(llmConfiguration).isMockMode();
        verifyNoInteractions(openAiClient);
    }

    @Test
    void testGenerationWithDefaults() {
        when(llmConfiguration.isMockMode()).thenReturn(false);
        when(openAiConfiguration.getModel()).thenReturn("gpt-4.1-mini");
        when(openAiClient.generate(any(GenerateRequest.class)))
                .thenReturn(new OpenAiGenerationResult("Default test content", usage()));

        GenerateRequest request = new GenerateRequest();
        request.setPrompt("Test prompt");

        GenerateResponse response = llmGatewayService.generate(request);

        assertNotNull(response);
        assertEquals("OPENAI", response.getProvider());
        assertEquals("gpt-4.1-mini", response.getModel());
        assertEquals("Default test content", response.getResponse());
    }

    private LlmUsage usage() {
        return LlmUsage.builder()
                .provider("OPENAI")
                .model("gpt-4.1-mini")
                .inputTokens(4200L)
                .outputTokens(3100L)
                .totalTokens(7300L)
                .build();
    }
}
