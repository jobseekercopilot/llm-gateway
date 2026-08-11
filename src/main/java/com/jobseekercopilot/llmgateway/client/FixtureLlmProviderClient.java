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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
            List<EvidenceValue> evidence = StreamSupport.stream(
                            envelope.path("approvedEvidence")
                                    .path("records")
                                    .spliterator(),
                            false)
                    .filter(JsonNode::isObject)
                    .filter(record -> record.path("evidenceId").isTextual())
                    .filter(record -> record.path("value").isTextual())
                    .map(record -> new EvidenceValue(
                            record.path("evidenceId").textValue(),
                            record.path("value").textValue(),
                            record.path("source").asText("PROFILE"),
                            record.path("purpose").asText("BOTH"),
                            record.path("category").asText(""),
                            record.path("factType").isTextual()
                                    ? record.path("factType").textValue()
                                    : null))
                    .toList();
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
            coverLetter.put("openingParagraph", "Please consider my application for this role.");
            if (output.path("generationNotes") instanceof ObjectNode notes) {
                notes.put("tailoringSummary",
                        "Fixture-generated documents tailored to the selected vacancy.");
            }
            contextualiseEvidenceLedger(output, cv, coverLetter, evidence, jobTitle);
            retainRequestedSchemaFields(command, output);
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new GenerationBoundaryException(
                    "The CV and cover-letter fixture payload could not be processed.");
        }
    }

    private void retainRequestedSchemaFields(
            GenerationCommand command,
            ObjectNode output
    ) {
        if (command.jsonSchema() == null
                || !(command.jsonSchema().path("properties") instanceof ObjectNode properties)
                || properties.isEmpty()) {
            return;
        }
        List<String> allowedFields = new ArrayList<>();
        properties.fieldNames().forEachRemaining(allowedFields::add);
        retainPurposeClaims(
                output,
                properties.has("cv"),
                properties.has("coverLetter"),
                properties.at(
                                "/claims/items/properties/contentPaths/items/pattern")
                        .asText());
        output.retain(allowedFields);
    }

    private void retainPurposeClaims(
            ObjectNode output,
            boolean includesCv,
            boolean includesCoverLetter,
            String claimPathPattern
    ) {
        if (includesCv == includesCoverLetter
                || !(output.path("claims") instanceof ArrayNode claims)) {
            return;
        }
        String requiredPrefix = includesCv ? "/cv/" : "/coverLetter/";
        ArrayNode purposeClaims = objectMapper.createArrayNode();
        StreamSupport.stream(claims.spliterator(), false)
                .filter(claim -> claim.path("contentPaths").isArray())
                .filter(claim -> claim.path("contentPaths").size() > 0)
                .filter(claim -> StreamSupport.stream(
                                claim.path("contentPaths").spliterator(), false)
                        .allMatch(path -> path.isTextual()
                                && path.textValue().startsWith(requiredPrefix)))
                .forEach(purposeClaims::add);
        if (includesCoverLetter
                && "/coverLetter/title".matches(claimPathPattern)
                && StreamSupport.stream(purposeClaims.spliterator(), false)
                        .noneMatch(claim -> StreamSupport.stream(
                                        claim.path("contentPaths").spliterator(), false)
                                .anyMatch(path -> "/coverLetter/title".equals(
                                        path.asText())))) {
            appendClaim(
                    purposeClaims,
                    "CLAIM-004",
                    "/coverLetter/title",
                    "JOB.TITLE");
        }
        output.set("claims", purposeClaims);
    }

    private void contextualiseEvidenceLedger(
            ObjectNode output,
            ObjectNode cv,
            ObjectNode coverLetter,
            List<EvidenceValue> evidence,
            String jobTitle
    ) {
        if (!(output.path("claims") instanceof ArrayNode claims)) {
            return;
        }

        EvidenceValue cvEvidence = profileEvidence(evidence, "CV")
                .or(() -> profileEvidence(evidence, "COVER_LETTER"))
                .orElseThrow(() -> new GenerationBoundaryException(
                        "The CV fixture request has no supported confirmed evidence."));
        EvidenceValue coverLetterEvidence =
                profileEvidence(evidence, "COVER_LETTER")
                        .or(() -> profileEvidence(evidence, "CV"))
                        .orElseThrow(() -> new GenerationBoundaryException(
                                "The cover-letter fixture request has no supported confirmed evidence."));

        contextualiseCvProject(cv, claims, evidence);

        cv.put("personalSummary",
                "Candidate profile includes " + cvEvidence.value()
                        + " and is tailored to the " + jobTitle + " role.");
        if (coverLetter.path("bodyParagraphs") instanceof ArrayNode bodyParagraphs
                && bodyParagraphs.size() >= 4) {
            setBodyParagraph(bodyParagraphs, 0,
                    "My profile includes " + coverLetterEvidence.value() + ".",
                    List.of(coverLetterEvidence.id()));
            setBodyParagraph(bodyParagraphs, 1,
                    "That confirmed experience is relevant to the " + jobTitle + " role.",
                    List.of(coverLetterEvidence.id(), "JOB.TITLE"));
            setBodyParagraph(bodyParagraphs, 2,
                    "I would bring that confirmed experience to the role.",
                    List.of(coverLetterEvidence.id()));
            setBodyParagraph(bodyParagraphs, 3,
                    "I welcome the opportunity to discuss how that experience supports the "
                            + jobTitle + " role.",
                    List.of(coverLetterEvidence.id(), "JOB.TITLE"));
        } else {
            throw new GenerationBoundaryException(
                    "The cover-letter fixture requires four structured body paragraphs.");
        }
        coverLetter.put(
                "closingParagraph",
                "Thank you for considering my application.");

        if (output.path("personalSummaryClaim") instanceof ObjectNode personalSummaryClaim) {
            ArrayNode evidenceIds = personalSummaryClaim.putArray("evidenceIds");
            evidenceIds.add(cvEvidence.id());
            evidenceIds.add("JOB.TITLE");
        } else {
            throw new GenerationBoundaryException(
                    "The CV fixture requires a dedicated personal-summary claim.");
        }

        setClaimEvidence(claims, "/cv/targetRole", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/jobTitle", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/companyName", List.of("JOB.COMPANY"));
    }

    private void setBodyParagraph(
            ArrayNode paragraphs,
            int index,
            String text,
            List<String> evidenceIds
    ) {
        if (!(paragraphs.get(index) instanceof ObjectNode paragraph)) {
            throw new GenerationBoundaryException(
                    "The cover-letter fixture body paragraph has an invalid structure.");
        }
        paragraph.put("text", text);
        paragraph.put("disposition", "REWORDED");
        ArrayNode approvedEvidence = paragraph.putArray("evidenceIds");
        evidenceIds.forEach(approvedEvidence::add);
    }

    private void contextualiseCvProject(
            ObjectNode cv,
            ArrayNode claims,
            List<EvidenceValue> evidence
    ) {
        List<EvidenceValue> projectEvidence = evidence.stream()
                .filter(item -> item.supports("CV"))
                .filter(item -> "PROJECT".equals(item.category()))
                .toList();
        if (projectEvidence.isEmpty()) {
            return;
        }

        EvidenceValue title = requiredFact(projectEvidence, "HEADING");
        EvidenceValue role = optionalFact(projectEvidence, "PROJECT_ROLE").orElse(null);
        EvidenceValue description = requiredFact(projectEvidence, "DESCRIPTION");
        EvidenceValue startDate = optionalFact(projectEvidence, "START_DATE").orElse(null);
        EvidenceValue endDate = optionalFact(projectEvidence, "END_DATE").orElse(null);

        ArrayNode projects = cv.putArray("projects");
        ObjectNode project = projects.addObject();
        project.put("title", title.value());
        project.put("role", role == null ? "" : role.value());
        project.put("context", "");
        project.put("startDate", startDate == null ? "" : startDate.value());
        project.put("endDate", endDate == null ? "" : endDate.value());
        project.put("description", description.value());
        project.putArray("highlights");

        appendClaim(claims, "CLAIM-101", "/cv/projects/0/title", title.id());
        if (role != null) appendClaim(claims, "CLAIM-102", "/cv/projects/0/role", role.id());
        if (startDate != null) appendClaim(
                claims, "CLAIM-103", "/cv/projects/0/startDate", startDate.id());
        if (endDate != null) appendClaim(
                claims, "CLAIM-104", "/cv/projects/0/endDate", endDate.id());
        appendClaim(
                claims, "CLAIM-105", "/cv/projects/0/description", description.id());
    }

    private EvidenceValue requiredFact(
            List<EvidenceValue> evidence,
            String factType
    ) {
        return optionalFact(evidence, factType)
                .orElseThrow(() -> new GenerationBoundaryException(
                        "The selected project fixture is missing " + factType + "."));
    }

    private Optional<EvidenceValue> optionalFact(
            List<EvidenceValue> evidence,
            String factType
    ) {
        return evidence.stream()
                .filter(item -> factType.equals(item.factType()))
                .findFirst();
    }

    private void appendClaim(
            ArrayNode claims,
            String claimId,
            String contentPath,
            String evidenceId
    ) {
        ObjectNode claim = claims.addObject();
        claim.put("claimId", claimId);
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds").add(evidenceId);
        claim.putArray("contentPaths").add(contentPath);
        claim.put("reviewText", "");
    }

    private Optional<EvidenceValue> profileEvidence(
            List<EvidenceValue> evidence,
            String purpose) {
        for (String factType : List.of(
                "DESCRIPTION",
                "RESPONSIBILITIES",
                "RESPONSIBILITY",
                "ACHIEVEMENTS",
                "DEMONSTRATED_SKILL",
                "ROLE_TITLE",
                "QUALIFICATION_TITLE",
                "LEGACY_PROFILE")) {
            Optional<EvidenceValue> match = evidence.stream()
                    .filter(item -> item.supports(purpose))
                    .filter(item -> factType.equals(item.factType())
                            || ("LEGACY_PROFILE".equals(factType)
                                    && item.id().startsWith("PROFILE.")))
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

    private String requiredEvidence(
            List<EvidenceValue> evidence,
            String evidenceId) {
        return evidence.stream()
                .filter(item -> evidenceId.equals(item.id()))
                .map(EvidenceValue::value)
                .filter(value -> !value.isBlank())
                .findFirst()
                .orElseThrow(() -> new GenerationBoundaryException(
                        "The CV and cover-letter fixture request is missing required job evidence."));
    }

    private String text(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private long number(Long value) {
        return value == null ? 0L : value;
    }

    private record EvidenceValue(
            String id,
            String value,
            String source,
            String purpose,
            String category,
            String factType) {

        private boolean supports(String requestedPurpose) {
            return ("EVIDENCE_SNAPSHOT".equals(source)
                            && (requestedPurpose.equals(purpose)
                                    || "BOTH".equals(purpose)))
                    || id.startsWith("PROFILE.");
        }
    }
}
