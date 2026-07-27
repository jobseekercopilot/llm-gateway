package com.jobseekercopilot.llmgateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobseekercopilot.generated.systemdataservice.api.FixtureControllerApi;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmResponse;
import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.dto.GenerationOutputFormat;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import org.junit.jupiter.api.Test;

class FixtureLlmProviderClientTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FixtureControllerApi fixtureControllerApi = mock(FixtureControllerApi.class);
    private final FixtureLlmProviderClient client =
            new FixtureLlmProviderClient(properties(), fixtureControllerApi, objectMapper);

    @Test
    void contextualisesDocumentFixtureFromApprovedJobEvidence() throws Exception {
        when(fixtureControllerApi.llm(any())).thenReturn(response(documentFixture()));

        JsonNode output = objectMapper.readTree(client.generate(command("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"JOB.TITLE","value":"Backend Developer"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Recruitment Ltd"}
                    ]
                  }
                }
                """)).output());

        assertEquals("Tailored Backend Developer CV", output.at("/cv/title").textValue());
        assertEquals("Backend Developer", output.at("/cv/targetRole").textValue());
        assertEquals("Backend Developer Cover Letter", output.at("/coverLetter/title").textValue());
        assertEquals("Backend Developer", output.at("/coverLetter/jobTitle").textValue());
        assertEquals("Example Recruitment Ltd", output.at("/coverLetter/companyName").textValue());
        assertEquals(
                "I am applying for the Backend Developer role.",
                output.at("/coverLetter/openingParagraph").textValue());
    }

    @Test
    void failsClosedWhenRequiredJobEvidenceIsMissing() {
        when(fixtureControllerApi.llm(any())).thenReturn(response(documentFixture()));

        assertThrows(GenerationBoundaryException.class, () -> client.generate(command("""
                {"approvedEvidence":{"records":[{"evidenceId":"JOB.TITLE","value":"Developer"}]}}
                """)));
    }

    @Test
    void leavesOtherFixtureTasksUnchanged() {
        when(fixtureControllerApi.llm(any())).thenReturn(response("{\"message\":\"unchanged\"}"));

        String output = client.generate(new GenerationCommand(
                "GENERAL_GENERATION",
                "trusted",
                "untrusted",
                GenerationOutputFormat.TEXT,
                null,
                null,
                null,
                1000,
                0.0)).output();

        assertEquals("{\"message\":\"unchanged\"}", output);
    }

    private GenerationCommand command(String untrustedInput) {
        return new GenerationCommand(
                "CV_COVER_LETTER_GENERATION",
                "trusted",
                untrustedInput,
                GenerationOutputFormat.JSON_SCHEMA,
                "cv-cover-letter",
                "1.0",
                objectMapper.createObjectNode(),
                4000,
                0.0);
    }

    private FixtureLlmResponse response(String output) {
        return new FixtureLlmResponse()
                .response(output)
                .inputTokens(10L)
                .outputTokens(20L)
                .totalTokens(30L)
                .provider("FIXTURE")
                .model("fixture-llm");
    }

    private FixtureProperties properties() {
        FixtureProperties properties = new FixtureProperties();
        properties.setDatasetId("uk-software-developer-demo");
        properties.setDatasetVersion("1.0.0");
        properties.setScenario("DEMO_READY");
        return properties;
    }

    private String documentFixture() {
        return """
                {
                  "cv":{"title":"Old CV","targetRole":"Old role"},
                  "coverLetter":{
                    "title":"Old letter",
                    "jobTitle":"Old role",
                    "companyName":"Old company",
                    "openingParagraph":"Old opening"
                  },
                  "generationNotes":{"tailoringSummary":"Old summary"}
                }
                """;
    }
}
