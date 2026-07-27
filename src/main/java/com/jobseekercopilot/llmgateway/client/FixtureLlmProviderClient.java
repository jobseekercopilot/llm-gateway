package com.jobseekercopilot.llmgateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobseekercopilot.llmgateway.config.FixtureProperties;
import com.jobseekercopilot.llmgateway.domain.GenerationCommand;
import com.jobseekercopilot.llmgateway.domain.GenerationFinishReason;
import com.jobseekercopilot.llmgateway.domain.ProviderGenerationResult;
import com.jobseekercopilot.llmgateway.dto.GenerationUsage;
import com.jobseekercopilot.llmgateway.exception.GenerationBoundaryException;
import com.jobseekercopilot.generated.systemdataservice.api.FixtureControllerApi;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmRequest;
import com.jobseekercopilot.generated.systemdataservice.model.FixtureLlmResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "external-provider", name = "mode", havingValue = "FIXTURE")
public class FixtureLlmProviderClient implements LlmProviderClient {
    private static final Logger log = LoggerFactory.getLogger(FixtureLlmProviderClient.class);
    private final FixtureProperties fixtureProperties;
    private final FixtureControllerApi fixtureControllerApi;
    private final ObjectMapper objectMapper;

    public FixtureLlmProviderClient(
            FixtureProperties fixtureProperties,
            FixtureControllerApi fixtureControllerApi,
            ObjectMapper objectMapper
    ) {
        this.fixtureProperties = fixtureProperties;
        this.fixtureControllerApi = fixtureControllerApi;
        this.objectMapper = objectMapper;
    }

    @Override
    public ProviderGenerationResult generate(GenerationCommand command) {
        FixtureLlmRequest payload = new FixtureLlmRequest()
                .datasetId(fixtureProperties.getDatasetId())
                .datasetVersion(fixtureProperties.getDatasetVersion())
                .scenario(fixtureProperties.getScenario())
                .operation(command.task());
        FixtureLlmResponse body = fixtureControllerApi.llm(payload);
        log.info("LLM fixture response returned datasetId={} scenario={}", fixtureProperties.getDatasetId(), fixtureProperties.getScenario());
        return new ProviderGenerationResult(
                contextualiseFixture(command, text(body == null ? null : body.getResponse(), "{}")),
                new GenerationUsage(
                        number(body == null ? null : body.getInputTokens()),
                        number(body == null ? null : body.getOutputTokens()),
                        number(body == null ? null : body.getTotalTokens())
                ),
                GenerationFinishReason.COMPLETED,
                text(body == null ? null : body.getProvider(), "fixture"),
                text(body == null ? null : body.getModel(), "fixture-llm")
        );
    }

    private String contextualiseFixture(GenerationCommand command, String response) {
        if (!"CV_COVER_LETTER_GENERATION".equalsIgnoreCase(command.task())) {
            return response;
        }
        try {
            JsonNode envelope = objectMapper.readTree(command.untrustedInput());
            Map<String, String> evidence = StreamSupport.stream(
                            envelope.path("approvedEvidence").path("records").spliterator(), false)
                    .filter(JsonNode::isObject)
                    .filter(record -> record.path("evidenceId").isTextual())
                    .filter(record -> record.path("value").isTextual())
                    .collect(Collectors.toMap(
                            record -> record.path("evidenceId").textValue(),
                            record -> record.path("value").textValue(),
                            (first, ignored) -> first,
                            LinkedHashMap::new));
            String jobTitle = requiredEvidence(evidence, "JOB.TITLE");
            String companyName = requiredEvidence(evidence, "JOB.COMPANY");

            JsonNode parsedResponse = objectMapper.readTree(response);
            if (!(parsedResponse instanceof ObjectNode output)
                    || !(output.path("cv") instanceof ObjectNode cv)
                    || !(output.path("coverLetter") instanceof ObjectNode coverLetter)) {
                throw new GenerationBoundaryException(
                        "The CV and cover-letter fixture response has an invalid structure.");
            }
            cv.put("title", "Tailored " + jobTitle + " CV");
            cv.put("targetRole", jobTitle);
            coverLetter.put("title", jobTitle + " Cover Letter");
            coverLetter.put("jobTitle", jobTitle);
            coverLetter.put("companyName", companyName);
            coverLetter.put("openingParagraph", "I am applying for the " + jobTitle + " role.");
            if (output.path("generationNotes") instanceof ObjectNode notes) {
                notes.put("tailoringSummary",
                        "Fixture-generated documents tailored to the selected vacancy.");
            }
            contextualiseEvidenceLedger(output, cv, coverLetter, evidence, jobTitle);
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new GenerationBoundaryException(
                    "The CV and cover-letter fixture payload could not be processed.");
        }
    }

    private void contextualiseEvidenceLedger(
            ObjectNode output,
            ObjectNode cv,
            ObjectNode coverLetter,
            Map<String, String> evidence,
            String jobTitle
    ) {
        if (!(output.path("claims") instanceof ArrayNode claims)) {
            return;
        }

        EvidenceValue profileEvidence = profileEvidence(evidence)
                .orElseThrow(() -> new GenerationBoundaryException(
                        "The CV and cover-letter fixture request has no supported profile evidence."));

        cv.put("personalSummary",
                "Candidate profile includes " + profileEvidence.value()
                        + " and is tailored to the " + jobTitle + " role.");
        if (coverLetter.path("bodyParagraphs") instanceof ArrayNode bodyParagraphs
                && bodyParagraphs.size() >= 2) {
            bodyParagraphs.set(
                    0,
                    objectMapper.getNodeFactory().textNode(
                            "My profile includes " + profileEvidence.value() + "."));
            bodyParagraphs.set(
                    1,
                    objectMapper.getNodeFactory().textNode(
                            "I have reviewed the requirements for the " + jobTitle + " role."));
        }
        coverLetter.put(
                "closingParagraph",
                "Thank you for considering my application for the " + jobTitle + " role.");

        setClaimEvidence(claims, "/cv/title", List.of(profileEvidence.id(), "JOB.TITLE"));
        setClaimEvidence(claims, "/cv/targetRole", List.of("JOB.TITLE"));
        setClaimEvidence(
                claims,
                "/cv/personalSummary",
                List.of(profileEvidence.id(), "JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/title", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/jobTitle", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/companyName", List.of("JOB.COMPANY"));
        setClaimEvidence(
                claims,
                "/coverLetter/openingParagraph",
                List.of("REQUEST.GENERATION_INTENT", "JOB.TITLE"));
        setClaimEvidence(
                claims,
                "/coverLetter/bodyParagraphs/0",
                List.of(profileEvidence.id()));
        setClaimEvidence(claims, "/coverLetter/bodyParagraphs/1", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/closingParagraph", List.of("JOB.TITLE"));
    }

    private Optional<EvidenceValue> profileEvidence(Map<String, String> evidence) {
        for (Predicate<String> supported : List.<Predicate<String>>of(
                id -> id.startsWith("PROFILE.EMPLOYMENT.") && id.endsWith(".RESPONSIBILITIES"),
                id -> id.startsWith("PROFILE.EMPLOYMENT.") && id.endsWith(".JOB_TITLE"),
                id -> id.startsWith("PROFILE.SKILL."),
                id -> id.startsWith("PROFILE.QUALIFICATION.") && id.endsWith(".NAME"),
                id -> id.startsWith("PROFILE.TARGET_ROLE."),
                id -> id.startsWith("PROFILE."))) {
            Optional<EvidenceValue> match = evidence.entrySet().stream()
                    .filter(entry -> supported.test(entry.getKey()))
                    .map(entry -> new EvidenceValue(entry.getKey(), entry.getValue()))
                    .findFirst();
            if (match.isPresent()) {
                return match;
            }
        }
        return Optional.empty();
    }

    private void setClaimEvidence(ArrayNode claims, String contentPath, List<String> evidenceIds) {
        StreamSupport.stream(claims.spliterator(), false)
                .filter(ObjectNode.class::isInstance)
                .map(ObjectNode.class::cast)
                .filter(claim -> StreamSupport.stream(
                                claim.path("contentPaths").spliterator(), false)
                        .anyMatch(path -> path.isTextual() && contentPath.equals(path.textValue())))
                .findFirst()
                .ifPresent(claim -> {
                    ArrayNode approvedEvidence = objectMapper.createArrayNode();
                    evidenceIds.forEach(approvedEvidence::add);
                    claim.set("evidenceIds", approvedEvidence);
                });
    }

    private String requiredEvidence(Map<String, String> evidence, String evidenceId) {
        String value = evidence.get(evidenceId);
        if (value == null || value.isBlank()) {
            throw new GenerationBoundaryException(
                    "The CV and cover-letter fixture request is missing required job evidence.");
        }
        return value;
    }

    private String text(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private record EvidenceValue(String id, String value) {
    }
}
