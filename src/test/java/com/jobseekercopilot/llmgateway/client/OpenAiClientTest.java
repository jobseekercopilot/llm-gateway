package com.jobseekercopilot.llmgateway.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.llmgateway.exception.GenerationRefusedException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiClientTest {

    @Test
    void springCanConstructTheLiveModeAdapter() {
        new ApplicationContextRunner()
                .withPropertyValues("external-provider.mode=LIVE")
                .withUserConfiguration(OpenAiConfiguration.class, OpenAiClient.class)
                .run(context ->
                        assertEquals(1, context.getBeansOfType(OpenAiClient.class).size()));
    }

    @Test
    void adapterKeepsTrustedAndUntrustedMessagesSeparateAndMapsStrictSchema() throws Exception {
        OpenAiConfiguration configuration = configuration();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo(configuration.getEndpoint()))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer runtime-secret"))
                .andExpect(header("OpenAI-Organization", "org-jobseeker-copilot"))
                .andExpect(header("OpenAI-Project", "proj_jobseeker_copilot_beta"))
                .andExpect(jsonPath("$.*", hasSize(7)))
                .andExpect(jsonPath("$.messages[0].role").value("developer"))
                .andExpect(jsonPath("$.messages[0].content").value("Trusted instructions"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("Untrusted input"))
                .andExpect(jsonPath("$.max_completion_tokens").value(1000))
                .andExpect(jsonPath("$.temperature").value(0.2))
                .andExpect(jsonPath("$.store").value(false))
                .andExpect(jsonPath("$.service_tier").value("default"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("document-output"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andRespond(withSuccess("""
                        {
                          "model": "configured-model",
                          "service_tier": "default",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {"content": "{\\"document\\":\\"ok\\"}"}
                          }],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(configuration, restTemplate);
        ProviderGenerationResult result = client.generate(new GenerationCommand(
                "DOCUMENT_DRAFT",
                "Trusted instructions",
                "Untrusted input",
                GenerationOutputFormat.JSON_SCHEMA,
                "document-output",
                "1.0",
                new ObjectMapper().readTree("""
                        {"type":"object","additionalProperties":false}
                        """),
                1000,
                0.2
        ));

        server.verify();
        assertEquals("{\"document\":\"ok\"}", result.output());
        assertEquals(GenerationFinishReason.COMPLETED, result.finishReason());
        assertEquals("openai", result.adapterId());
        assertEquals("configured-model", result.modelId());
        assertEquals(20L, result.usage().totalTokens());
    }

    @Test
    void adapterTurnsProviderRefusalIntoStableDomainFailure() {
        OpenAiConfiguration configuration = configuration();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo(configuration.getEndpoint()))
                .andRespond(withSuccess("""
                        {
                          "model": "configured-model",
                          "service_tier": "default",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {"content": null, "refusal": "provider detail"}
                          }],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(configuration, restTemplate);
        GenerationCommand command = new GenerationCommand(
                "DOCUMENT_DRAFT",
                "Trusted instructions",
                "Untrusted input",
                GenerationOutputFormat.TEXT,
                null,
                null,
                null,
                1000,
                0.2
        );

        assertThrows(GenerationRefusedException.class, () -> client.generate(command));
        server.verify();
    }

    @Test
    void adapterRejectsIncompleteProviderMetadataThroughTheStableBoundary() {
        OpenAiConfiguration configuration = configuration();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo(configuration.getEndpoint()))
                .andRespond(withSuccess("""
                        {
                          "model": "configured-model",
                          "service_tier": "default",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {"content": "generated output"}
                          }],
                          "usage": {
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(configuration, restTemplate);

        assertThrows(GenerationBoundaryException.class, () -> client.generate(textCommand()));
        server.verify();
    }

    @Test
    void adapterRejectsUnexpectedServiceTierBeforeCostAccounting() {
        OpenAiConfiguration configuration = configuration();
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(once(), requestTo(configuration.getEndpoint()))
                .andRespond(withSuccess("""
                        {
                          "model": "configured-model",
                          "service_tier": "priority",
                          "choices": [{
                            "finish_reason": "stop",
                            "message": {"content": "generated output"}
                          }],
                          "usage": {
                            "prompt_tokens": 12,
                            "completion_tokens": 8,
                            "total_tokens": 20
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        OpenAiClient client = new OpenAiClient(configuration, restTemplate);

        assertThrows(GenerationBoundaryException.class, () -> client.generate(textCommand()));
        server.verify();
    }

    private GenerationCommand textCommand() {
        return new GenerationCommand(
                "DOCUMENT_DRAFT",
                "Trusted instructions",
                "Untrusted input",
                GenerationOutputFormat.TEXT,
                null,
                null,
                null,
                1000,
                0.2
        );
    }

    private OpenAiConfiguration configuration() {
        OpenAiConfiguration configuration = new OpenAiConfiguration();
        configuration.setApiKey("runtime-secret");
        configuration.setModel("configured-model");
        configuration.setEndpoint("https://api.openai.com/v1/chat/completions");
        configuration.setOrganizationId("org-jobseeker-copilot");
        configuration.setProjectId("proj_jobseeker_copilot_beta");
        return configuration;
    }
}
