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
                "Please consider my application for this role.",
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
                output.at("/coverLetter/bodyParagraphs/0/text").textValue());
        assertEquals(
                "PROFILE.EMPLOYMENT.1.RESPONSIBILITIES",
                output.at("/coverLetter/bodyParagraphs/0/evidenceIds/0").textValue());
    }

    @Test
    void omitsEmploymentEvidenceWhenClaimantHasNoEmploymentHistory() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Java"}
                """);

        assertEquals(
                "My profile includes Java.",
                output.at("/coverLetter/bodyParagraphs/0/text").textValue());
        assertFalse(output.toString().contains("PROFILE.EMPLOYMENT."));
    }

    @Test
    void usesSupportedSkillWhenQualificationsExistWithoutEmployment() throws Exception {
        JsonNode output = generateFullFixture("""
                {"evidenceId":"PROFILE.SKILL.1","value":"Spring Boot"},
                {"evidenceId":"PROFILE.QUALIFICATION.1.NAME","value":"BSc Computing"}
                """);

        assertTrue(output.at("/cv/personalSummary").textValue().contains("Spring Boot"));
        assertEquals("PROFILE.SKILL.1",
                output.at("/personalSummaryClaim/evidenceIds/0").textValue());
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
                      {"evidenceId":"cccccccc-0000-4000-8000-000000000003","value":"Application Delivery Platform",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "factType":"HEADING","category":"PROJECT"},
                      {"evidenceId":"dddddddd-0000-4000-8000-000000000004","value":"Built a reliable Java delivery platform",
                       "source":"EVIDENCE_SNAPSHOT","purpose":"CV",
                       "factType":"DESCRIPTION","category":"PROJECT"},
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
        assertEquals(
                "Application Delivery Platform",
                output.at("/cv/projects/0/title").asText());
        assertTrue(output.at("/coverLetter/bodyParagraphs/0")
                .path("text").asText().contains("community mentoring"));
        assertEquals(
                "dddddddd-0000-4000-8000-000000000004",
                claimFor(output, "/cv/projects/0/description")
                        .at("/evidenceIds/0").asText());
        assertEquals(
                coverFact,
                output.at("/coverLetter/bodyParagraphs/0/evidenceIds/0").asText());
        assertFalse(claimFor(output, "/cv/projects/0/description").path("evidenceIds")
                .toString().contains(coverFact));
        assertFalse(output.at("/coverLetter/bodyParagraphs/0/evidenceIds")
                .toString().contains(cvFact));
    }

    @Test
    void supportsOnePurposePerBoundedGenerationRequest() throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));

        JsonNode cvOutput = objectMapper.readTree(client.generate(command("""
                {"approvedEvidence":{"records":[
                  {"evidenceId":"aaaaaaaa-0000-4000-8000-000000000001",
                   "value":"Selected project delivery","source":"EVIDENCE_SNAPSHOT",
                   "purpose":"CV","factType":"DESCRIPTION","category":"PROJECT"},
                  {"evidenceId":"bbbbbbbb-0000-4000-8000-000000000002",
                   "value":"Delivery platform","source":"EVIDENCE_SNAPSHOT",
                   "purpose":"CV","factType":"HEADING","category":"PROJECT"},
                  {"evidenceId":"JOB.TITLE","value":"Java Developer","source":"JOB","purpose":"BOTH"},
                  {"evidenceId":"JOB.COMPANY","value":"Example Ltd","source":"JOB","purpose":"BOTH"}
                ]}}
                """)).output());

        assertEquals("Delivery platform", cvOutput.at("/cv/projects/0/title").asText());

        JsonNode coverOutput = objectMapper.readTree(client.generate(command("""
                {"approvedEvidence":{"records":[
                  {"evidenceId":"cccccccc-0000-4000-8000-000000000003",
                   "value":"Selected project delivery","source":"EVIDENCE_SNAPSHOT",
                   "purpose":"COVER_LETTER","factType":"DESCRIPTION","category":"PROJECT"},
                  {"evidenceId":"JOB.TITLE","value":"Java Developer","source":"JOB","purpose":"BOTH"},
                  {"evidenceId":"JOB.COMPANY","value":"Example Ltd","source":"JOB","purpose":"BOTH"}
                ]}}
                """)).output());

        assertTrue(coverOutput.at("/coverLetter/bodyParagraphs/0")
                .path("text").asText().contains("Selected project delivery"));
    }

    @Test
    void projectsCombinedFixtureIntoTheRequestedPurposeSchema() throws Exception {
        when(fixtureControllerApi.llm(any()))
                .thenReturn(response(fullDocumentFixture()));
        var schema = objectMapper.createObjectNode();
        var properties = schema.putObject("properties");
        properties.putObject("cv");
        properties.putObject("generationNotes");
        properties.putObject("personalSummaryClaim");
        properties.putObject("claims");

        GenerationCommand cvCommand = new GenerationCommand(
                "CV_COVER_LETTER_GENERATION",
                "trusted",
                """
                {"approvedEvidence":{"records":[
                  {"evidenceId":"PROFILE.SKILL.1","value":"Java"},
                  {"evidenceId":"JOB.TITLE","value":"Java Developer"},
                  {"evidenceId":"JOB.COMPANY","value":"Example Ltd"}
                ]}}
                """,
                GenerationOutputFormat.JSON_SCHEMA,
                "cv-cover-letter",
                "4.0.0",
                schema,
                4000,
                0.0);

        JsonNode output = objectMapper.readTree(client.generate(cvCommand).output());

        assertTrue(output.has("cv"));
        assertTrue(output.has("personalSummaryClaim"));
        assertFalse(output.has("coverLetter"));
        assertFalse(output.has("canonicalApplicationClaims"));
        assertThatEveryClaimUsesPrefix(output, "/cv/");

        var coverSchema = objectMapper.createObjectNode();
        var coverProperties = coverSchema.putObject("properties");
        coverProperties.putObject("coverLetter");
        coverProperties.putObject("generationNotes");
        coverProperties.putObject("canonicalApplicationClaims");
        coverProperties.putObject("claims")
                .putObject("items")
                .putObject("properties")
                .putObject("contentPaths")
                .putObject("items")
                .put("pattern", "^/coverLetter/(?:title|jobTitle|companyName)$");
        GenerationCommand coverCommand = new GenerationCommand(
                cvCommand.task(),
                cvCommand.trustedInstructions(),
                cvCommand.untrustedInput(),
                cvCommand.outputFormat(),
                cvCommand.schemaId(),
                cvCommand.schemaVersion(),
                coverSchema,
                cvCommand.maxOutputTokens(),
                cvCommand.temperature());

        JsonNode coverOutput = objectMapper.readTree(
                client.generate(coverCommand).output());

        assertTrue(coverOutput.has("coverLetter"));
        assertTrue(coverOutput.has("canonicalApplicationClaims"));
        assertFalse(coverOutput.has("cv"));
        assertFalse(coverOutput.has("personalSummaryClaim"));
        assertEquals("JOB.TITLE",
                claimFor(coverOutput, "/coverLetter/title")
                        .at("/evidenceIds/0").asText());
        assertThatEveryClaimUsesPrefix(coverOutput, "/coverLetter/");
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

    private JsonNode claimFor(JsonNode output, String contentPath) {
        for (JsonNode claim : output.path("claims")) {
            for (JsonNode path : claim.path("contentPaths")) {
                if (contentPath.equals(path.asText())) {
                    return claim;
                }
            }
        }
        throw new AssertionError("Missing claim for " + contentPath);
    }

    private void assertThatEveryClaimUsesPrefix(JsonNode output, String prefix) {
        assertFalse(output.path("claims").isEmpty());
        for (JsonNode claim : output.path("claims")) {
            for (JsonNode path : claim.path("contentPaths")) {
                assertTrue(path.asText().startsWith(prefix), path.asText());
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
                    "projects":[],
                    "qualifications":[],
                    "workHistory":[]
                  },
                  "coverLetter":{
                    "title":"Old letter",
                    "jobTitle":"Old role",
                    "companyName":"Old company",
                    "greeting":"Dear Hiring Manager,",
                    "openingParagraph":"Please consider my application for this role.",
                    "bodyParagraphs":[
                      {"text":"Old profile claim","disposition":"REWORDED","evidenceIds":["PROFILE.SKILL.1"]},
                      {"text":"Old job claim","disposition":"REWORDED","evidenceIds":["PROFILE.SKILL.1","JOB.TITLE"]},
                      {"text":"Old evidence claim","disposition":"REWORDED","evidenceIds":["PROFILE.SKILL.1"]},
                      {"text":"Old closing claim","disposition":"REWORDED","evidenceIds":["PROFILE.SKILL.1","JOB.TITLE"]}
                    ],
                    "closingParagraph":"Thank you for considering my application.",
                    "signOff":"Yours sincerely"
                  },
                  "generationNotes":{"assumptionsMade":[],"missingInformation":[],"tailoringSummary":"Old summary"},
                  "claims":[
                    {"claimId":"CLAIM-001","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.TITLE"],
                     "contentPaths":["/cv/targetRole"],"reviewText":""},
                    {"claimId":"CLAIM-002","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.TITLE"],
                     "contentPaths":["/coverLetter/jobTitle"],"reviewText":""},
                    {"claimId":"CLAIM-003","disposition":"SUPPORTED",
                     "evidenceIds":["JOB.COMPANY"],
                     "contentPaths":["/coverLetter/companyName"],"reviewText":""}
                  ],
                  "canonicalApplicationClaims":{
                    "opening":{"claimId":"CLAIM-9001","disposition":"SUPPORTED",
                      "generationIntentEvidenceId":"REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId":"JOB.TITLE","companyEvidenceId":"JOB.COMPANY",
                      "contentPath":"/coverLetter/openingParagraph","reviewText":""},
                    "closing":{"claimId":"CLAIM-9002","disposition":"SUPPORTED",
                      "generationIntentEvidenceId":"REQUEST.GENERATION_INTENT",
                      "jobTitleEvidenceId":"JOB.TITLE","companyEvidenceId":"JOB.COMPANY",
                      "contentPath":"/coverLetter/closingParagraph","reviewText":""}
                  },
                  "personalSummaryClaim":{"claimId":"CLAIM-9003","disposition":"REWORDED",
                    "evidenceIds":["PROFILE.SKILL.1","JOB.TITLE"],
                    "contentPath":"/cv/personalSummary","reviewText":""}
                }
                """;
    }
}
