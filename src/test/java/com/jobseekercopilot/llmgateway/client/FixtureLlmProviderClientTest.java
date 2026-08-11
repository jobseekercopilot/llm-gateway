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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
    void versionedFixtureKeepsStableFactsWithinTheirDocumentPurpose()
            throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));
        String cvFact =
                "aaaaaaaa-0000-4000-8000-000000000001";
        String coverFact =
                "bbbbbbbb-0000-4000-8000-000000000002";

        JsonNode output = objectMapper.readTree(client.generate(command("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"REQUEST.GENERATION_INTENT",
                       "value":"Generate application documents",
                       "source":"REQUEST","purpose":"BOTH"},
                      {"evidenceId":"%s","value":"Java",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "factType":"DEMONSTRATED_SKILL","category":"PROJECT"},
                      {"evidenceId":"%s","value":"community mentoring",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"COVER_LETTER",
                       "factType":"DESCRIPTION","category":"VOLUNTEERING"},
                      {"evidenceId":"JOB.TITLE","value":"Java Developer",
                       "source":"JOB","purpose":"BOTH"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Ltd",
                       "source":"JOB","purpose":"BOTH"}
                    ]
                  }
                }
                """.formatted(cvFact, coverFact))).output());

        assertTrue(output.at("/cv/personalSummary")
                .asText().contains("Java"));
        assertTrue(output.at("/coverLetter/bodyParagraphs/0")
                .asText().contains("community mentoring"));
        assertEquals(
                cvFact,
                output.at("/claims/2/evidenceIds/0").asText());
        assertEquals(
                coverFact,
                output.at("/claims/7/evidenceIds/0").asText());
        assertFalse(output.at("/claims/2/evidenceIds")
                .toString().contains(coverFact));
        assertFalse(output.at("/claims/7/evidenceIds")
                .toString().contains(cvFact));
    }

    @Test
    void projectsCvOnlyFixtureUsingCvEvidence() throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));

        JsonNode output = objectMapper.readTree(client.generate(selectedCommand("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"REQUEST.GENERATION_INTENT",
                       "value":"Generate an application CV","source":"REQUEST","purpose":"BOTH"},
                      {"evidenceId":"aaaaaaaa-0000-4000-8000-000000000001",
                       "value":"Delivered accessible Java services",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV","factType":"DESCRIPTION"},
                      {"evidenceId":"JOB.TITLE","value":"Java Developer","purpose":"BOTH"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Ltd","purpose":"BOTH"}
                    ]
                  }
                }
                """, "cv")).output());

        assertTrue(output.has("cv"));
        assertFalse(output.has("coverLetter"));
        assertFalse(output.has("canonicalApplicationClaims"));
        assertTrue(output.at("/cv/personalSummary").asText()
                .contains("Delivered accessible Java services"));
        assertEquals(
                "CLAIM-9003",
                output.at("/personalSummaryClaim/claimId").asText());
        assertTrue(output.at("/personalSummaryClaim/evidenceIds")
                .toString().contains(
                        "aaaaaaaa-0000-4000-8000-000000000001"));
        assertClaimsTarget(output, "/cv/");
        assertEquals(
                "/cv/targetRole",
                output.at("/claims/0/contentPaths/0").asText());
    }

    @Test
    void projectsStructuredProjectEvidenceWithoutLosingRoleOrDates() throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));

        JsonNode output = objectMapper.readTree(client.generate(selectedCommand("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"PROJECT.HEADING","value":"Delivery platform",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "category":"PROJECT","factType":"HEADING"},
                      {"evidenceId":"PROJECT.ROLE","value":"Lead developer",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "category":"PROJECT","factType":"PROJECT_ROLE"},
                      {"evidenceId":"PROJECT.DESCRIPTION","value":"Built secure workflows",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "category":"PROJECT","factType":"DESCRIPTION"},
                      {"evidenceId":"PROJECT.START","value":"2024-01-01",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "category":"PROJECT","factType":"START_DATE"},
                      {"evidenceId":"PROJECT.END","value":"2025-06-30",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "category":"PROJECT","factType":"END_DATE"},
                      {"evidenceId":"JOB.TITLE","value":"Java Developer","purpose":"BOTH"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Ltd","purpose":"BOTH"}
                    ]
                  }
                }
                """, "cv")).output());

        assertEquals("Delivery platform", output.at("/cv/projects/0/title").asText());
        assertEquals("Lead developer", output.at("/cv/projects/0/role").asText());
        assertEquals("Built secure workflows", output.at("/cv/projects/0/description").asText());
        assertEquals("2024-01-01", output.at("/cv/projects/0/startDate").asText());
        assertEquals("2025-06-30", output.at("/cv/projects/0/endDate").asText());
        assertTrue(output.path("claims").toString().contains("PROJECT.ROLE"));
    }

    @Test
    void projectsCoverLetterOnlyFixtureUsingCoverLetterEvidence() throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));

        JsonNode output = objectMapper.readTree(client.generate(selectedCommand("""
                {
                  "approvedEvidence": {
                    "records": [
                      {"evidenceId":"REQUEST.GENERATION_INTENT",
                       "value":"Generate an application cover letter","source":"REQUEST","purpose":"BOTH"},
                      {"evidenceId":"bbbbbbbb-0000-4000-8000-000000000002",
                       "value":"Built secure integration workflows",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"COVER_LETTER","factType":"DESCRIPTION"},
                      {"evidenceId":"JOB.TITLE","value":"Java Developer","purpose":"BOTH"},
                      {"evidenceId":"JOB.COMPANY","value":"Example Ltd","purpose":"BOTH"}
                    ]
                  }
                }
                """, "coverLetter")).output());

        assertFalse(output.has("cv"));
        assertTrue(output.has("coverLetter"));
        assertFalse(output.has("personalSummaryClaim"));
        assertTrue(output.at("/coverLetter/bodyParagraphs/0/text").asText()
                .contains("Built secure integration workflows"));
        assertEquals(
                "CLAIM-9001",
                output.at("/canonicalApplicationClaims/opening/claimId")
                        .asText());
        assertEquals(
                "Please consider my application for this role.",
                output.at("/coverLetter/openingParagraph").asText());
        assertEquals(4, output.at("/coverLetter/bodyParagraphs").size());
        assertClaimsTarget(output, "/coverLetter/");
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

    private GenerationCommand selectedCommand(
            String untrustedInput,
            String outputProperty) {
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode selectedOutput = properties.putObject(outputProperty);
        if ("cv".equals(outputProperty)) {
            selectedOutput.putObject("properties").putObject("projects");
            properties.putObject("personalSummaryClaim");
        } else {
            selectedOutput.putObject("properties")
                    .putObject("bodyParagraphs")
                    .putObject("items")
                    .put("type", "object");
            properties.putObject("canonicalApplicationClaims");
        }
        JsonNode schema = objectMapper.createObjectNode()
                .set("properties", properties);
        return new GenerationCommand(
                "CV_COVER_LETTER_GENERATION",
                "trusted",
                untrustedInput,
                GenerationOutputFormat.JSON_SCHEMA,
                "cv-cover-letter",
                "1.0",
                schema,
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

    private void assertClaimsTarget(JsonNode output, String prefix) {
        assertFalse(output.path("claims").isEmpty());
        for (JsonNode claim : output.path("claims")) {
            for (JsonNode contentPath : claim.path("contentPaths")) {
                assertTrue(contentPath.asText().startsWith(prefix));
            }
        }
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
                  ],
                  "canonicalApplicationClaims": {
                    "opening": {"claimId":"CLAIM-9001"},
                    "closing": {"claimId":"CLAIM-9002"}
                  },
                  "personalSummaryClaim": {
                    "claimId":"CLAIM-9003",
                    "evidenceIds":["PROFILE.SKILL.1"]
                  }
                }
                """;
    }
}
