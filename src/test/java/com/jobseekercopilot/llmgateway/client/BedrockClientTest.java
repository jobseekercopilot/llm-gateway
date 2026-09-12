package com.jobseekercopilot.llmgateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.llmgateway.config.BedrockConfiguration;
import com.jobseekercopilot.llmgateway.config.ProviderResilienceSettings;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.resilience.ProviderCircuitBreaker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class BedrockClientTest {

    // The exact structured-output schema shape the CV/cover-letter service sends:
    // nested objects, arrays, integer bounds and string enums. This is the schema
    // that previously failed conversion to the AWS SDK Document type.
    private static final String CV_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["cv"],
              "properties": {
                "cv": {
                  "type": "object",
                  "additionalProperties": false,
                  "required": ["title", "coreSkills"],
                  "properties": {
                    "title": {"type": "string", "pattern": "^[\\\\s\\\\S]{1,200}$"},
                    "coreSkills": {
                      "type": "array",
                      "minItems": 0,
                      "maxItems": 12,
                      "items": {
                        "type": "object",
                        "additionalProperties": false,
                        "required": ["name", "disposition"],
                        "properties": {
                          "name": {"type": "string"},
                          "disposition": {"type": "string", "enum": ["SUPPORTED", "REWORDED"]}
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    @Test
    void buildsStructuredOutputRequestFromRealJsonSchemaAndRoundTripsResponse() throws Exception {
        BedrockConfiguration configuration = configuration();
        AtomicReference<ConverseRequest> captured = new AtomicReference<>();

        // The provider returns the requested document as a tool-use block whose
        // input is an AWS SDK Document, mirroring the real Converse contract.
        Map<String, Document> cv = new LinkedHashMap<>();
        cv.put("title", Document.fromString("Senior Engineer CV"));
        Document document = Document.fromMap(Map.of("cv", Document.fromMap(cv)));

        BedrockRuntimeClient runtimeClient = new StubBedrockRuntimeClient(captured, ConverseResponse.builder()
                .stopReason(StopReason.TOOL_USE)
                .output(ConverseOutput.fromMessage(Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromToolUse(ToolUseBlock.builder()
                                .toolUseId("tool-1")
                                .name("emit_structured_output")
                                .input(document)
                                .build()))
                        .build()))
                .usage(TokenUsage.builder().inputTokens(120).outputTokens(80).totalTokens(200).build())
                .build());

        BedrockClient client = new BedrockClient(
                configuration,
                runtimeClient,
                new ObjectMapper(),
                new ProviderCircuitBreaker(ProviderResilienceSettings.fromBedrock(configuration)));

        ProviderGenerationResult result = client.generate(new GenerationCommand(
                "CV_COVER_LETTER_GENERATION",
                "Trusted instructions",
                "Untrusted input",
                GenerationOutputFormat.JSON_SCHEMA,
                "cv-cover-letter-output-cv",
                "4.0.0",
                new ObjectMapper().readTree(CV_SCHEMA),
                2500,
                0.25));

        // The request must carry the schema converted into the tool input schema
        // Document (the regression: this conversion previously threw).
        ConverseRequest request = captured.get();
        assertNotNull(request, "request was not sent to the provider");
        Document inputSchema = request.toolConfig().tools().get(0).toolSpec().inputSchema().json();
        assertTrue(inputSchema.isMap(), "tool input schema must be a Document map");
        assertEquals("object", inputSchema.asMap().get("type").asString());
        Document coreSkills = inputSchema.asMap().get("properties").asMap()
                .get("cv").asMap().get("properties").asMap().get("coreSkills");
        assertEquals(12, coreSkills.asMap().get("maxItems").asNumber().intValue());
        assertTrue(coreSkills.asMap().get("items").asMap().get("properties").asMap()
                .get("disposition").asMap().get("enum").isList());

        // The response Document must round-trip back to JSON output for the caller.
        assertEquals("{\"cv\":{\"title\":\"Senior Engineer CV\"}}", result.output());
        assertEquals(GenerationFinishReason.COMPLETED, result.finishReason());
        assertEquals("bedrock", result.adapterId());
        assertEquals("anthropic.claude-3-7-sonnet-20250219-v1:0", result.modelId());
        assertEquals(200L, result.usage().totalTokens());
        assertFalse(result.output().isBlank());
    }

    private BedrockConfiguration configuration() {
        BedrockConfiguration configuration = new BedrockConfiguration();
        configuration.setModelId("anthropic.claude-3-7-sonnet-20250219-v1:0");
        configuration.setRegion("eu-west-2");
        return configuration;
    }

    private static final class StubBedrockRuntimeClient implements BedrockRuntimeClient {
        private final AtomicReference<ConverseRequest> captured;
        private final ConverseResponse response;

        private StubBedrockRuntimeClient(AtomicReference<ConverseRequest> captured, ConverseResponse response) {
            this.captured = captured;
            this.response = response;
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            captured.set(request);
            return response;
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
            // no-op stub
        }
    }
}
