package com.jobseekercopilot.llmgateway.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.HashSet;
import java.util.Set;
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
    void usesEmploymentEvidenceWhenClaimantHasEmploymentHistory() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.EMPLOYMENT.1.RESPONSIBILITIES",
                 "value":"Delivered reliable customer-facing services"}
                """);

        assertEquals(
                "My profile includes Delivered reliable customer-facing services.",
                output.at("/coverLetter/bodyParagraphs/0").textValue());
        assertEquals(
                "PROFILE.EMPLOYMENT.1.RESPONSIBILITIES",
                output.at("/claims/7/evidenceIds/0").textValue());
    }

    @Test
    void omitsEmploymentEvidenceWhenClaimantHasNoEmploymentHistory() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Java"}
                """);

        assertEquals(
                "My profile includes Java.",
                output.at("/coverLetter/bodyParagraphs/0").textValue());
        assertFalse(output.toString().contains("PROFILE.EMPLOYMENT."));
    }

    @Test
    void usesSupportedSkillWhenQualificationsExistWithoutEmployment() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Spring Boot"},
                {"evidenceId":"PROFILE.QUALIFICATION.1.NAME","value":"BSc Computing"}
                """);

        assertTrue(output.at("/cv/personalSummary").textValue().contains("Spring Boot"));
        assertEquals("PROFILE.SKILL.1", output.at("/claims/2/evidenceIds/0").textValue());
        assertFalse(output.toString().contains("PROFILE.EMPLOYMENT."));
    }

    @Test
    void generatedCvClaimsContainOnlyApprovedEvidenceReferences() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Java"},
                {"evidenceId":"PROFILE.QUALIFICATION.1.NAME","value":"BSc Computing"}
                """);

        assertClaimEvidenceIsApproved(output, "/cv/", Set.of(
                "PROFILE.SKILL.1",
                "PROFILE.QUALIFICATION.1.NAME",
                "JOB.TITLE"));
    }

    @Test
    void generatedCoverLetterClaimsContainOnlyApprovedEvidenceReferences() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Java"}
                """);

        assertClaimEvidenceIsApproved(output, "/coverLetter/", Set.of(
                "PROFILE.SKILL.1",
                "REQUEST.GENERATION_INTENT",
                "JOB.TITLE",
                "JOB.COMPANY"));
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

    private JsonNode generateFullFixture(String profileEvidence) throws Exception {
        when(fixtureControllerApi.llm(any())).thenReturn(response(fullDocumentFixture()));
        return objectMapper.readTree(client.generate(command("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"REQUEST.GENERATION_INTENT",
                       "value":"Generate an application CV and cover letter"},
                      %s,
                      {"evidenceId":"JOB.TITLE","value":"Java Developer"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Recruitment Ltd"},
                      {"evidenceId":"JOB.DESCRIPTION","value":"Build reliable Java services"}
                    ]
                  }
                }
                """.formatted(profileEvidence))).output());
    }

    private void assertClaimEvidenceIsApproved(
            JsonNode output,
            String contentPathPrefix,
            Set<String> approvedEvidence
    ) {
        Set<String> observedEvidence = new HashSet<>();
        for (JsonNode claim : output.path("claims")) {
            boolean matchingClaim = false;
            for (JsonNode contentPath : claim.path("contentPaths")) {
                matchingClaim |= contentPath.asText().startsWith(contentPathPrefix);
            }
            if (matchingClaim) {
                for (JsonNode evidenceId : claim.path("evidenceIds")) {
                    observedEvidence.add(evidenceId.asText());
                    assertTrue(
                            approvedEvidence.contains(evidenceId.asText()),
                            "Unsupported evidence reference: " + evidenceId.asText());
                }
            }
        }
        assertFalse(observedEvidence.isEmpty());
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

    private String fullDocumentFixture() {
        return """
                {
                  "cv":{
                    "title":"Old CV",
                    "targetRole":"Old role",
                    "personalSummary":"Old summary",
                    "coreSkills":[],
                    "qualifications":[],
                    "workHistory":[]
                  },
                  "coverLetter":{
                    "title":"Old letter",
                    "jobTitle":"Old role",
                    "companyName":"Old company",
                    "greeting":"Dear Hiring Manager,",
                    "openingParagraph":"Old opening",
                    "bodyParagraphs":["Old profile claim","Old job claim"],
                    "closingParagraph":"Old closing",
                    "signOff":"Yours sincerely"
                  },
                  "generationNotes":{"tailoringSummary":"Old summary"},
                  "claims":[
                    {"claimId":"CLAIM-001","disposition":"REWORDED",
                     "evidenceIds":["PROFILE.SKILL.1","JOB.TITLE"],
                     "contentPaths":["/cv/title"],"reviewText":""},
                    {"claimId":"CLAIM-002","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.TITLE"],
                     "contentPaths":["/cv/targetRole"],"reviewText":""},
                    {"claimId":"CLAIM-003","disposition":"REWORDED",
                     "evidenceIds":["PROFILE.SKILL.1","JOB.DESCRIPTION"],
                     "contentPaths":["/cv/personalSummary"],"reviewText":""},
                    {"claimId":"CLAIM-004","disposition":"REWORDED",
                     "evidenceIds":["JOB.TITLE"],
                     "contentPaths":["/coverLetter/title"],"reviewText":""},
                    {"claimId":"CLAIM-005","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.TITLE"],
                     "contentPaths":["/coverLetter/jobTitle"],"reviewText":""},
                    {"claimId":"CLAIM-006","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.COMPANY"],
                     "contentPaths":["/coverLetter/companyName"],"reviewText":""},
                    {"claimId":"CLAIM-007","disposition":"REWORDED",
                     "evidenceIds":["REQUEST.GENERATION_INTENT","JOB.TITLE"],
                     "contentPaths":["/coverLetter/openingParagraph"],"reviewText":""},
                    {"claimId":"CLAIM-008","disposition":"REWORDED",
                     "evidenceIds":["PROFILE.SKILL.1",
                                    "PROFILE.EMPLOYMENT.1.RESPONSIBILITIES"],
                     "contentPaths":["/coverLetter/bodyParagraphs/0"],"reviewText":""},
                    {"claimId":"CLAIM-009","disposition":"REWORDED",
                     "evidenceIds":["JOB.DESCRIPTION"],
                     "contentPaths":["/coverLetter/bodyParagraphs/1"],"reviewText":""},
                    {"claimId":"CLAIM-010","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.DESCRIPTION"],
                     "contentPaths":["/coverLetter/closingParagraph"],"reviewText":""}
                  ]
                }
                """;
    }
}
