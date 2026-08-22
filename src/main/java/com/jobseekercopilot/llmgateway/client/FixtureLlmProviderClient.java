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
            if (!(parsedResponse instanceof ObjectNode output)) {
                throw new GenerationBoundaryException(
                        "The CV and cover-letter fixture response has an invalid structure.");
            }
            RequestedOutputs requestedOutputs = requestedOutputs(command.jsonSchema());
            JsonNode cvNode = output.path("cv");
            JsonNode coverLetterNode = output.path("coverLetter");
            if ((requestedOutputs.cv() && !(cvNode instanceof ObjectNode))
                    || (requestedOutputs.coverLetter()
                            && !(coverLetterNode instanceof ObjectNode))) {
                throw new GenerationBoundaryException(
                        "The CV and cover-letter fixture response has an invalid structure.");
            }
            ObjectNode cv = requestedOutputs.cv() ? (ObjectNode) cvNode : null;
            ObjectNode coverLetter = requestedOutputs.coverLetter()
                    ? (ObjectNode) coverLetterNode
                    : null;
            projectSelectedOutputs(output, requestedOutputs);
            if (cv != null) {
                cv.put("title", "Tailored " + jobTitle + " CV");
                cv.put("targetRole", jobTitle);
            }
            if (coverLetter != null) {
                coverLetter.put("title", jobTitle + " Cover Letter");
                coverLetter.put("jobTitle", jobTitle);
                coverLetter.put("companyName", companyName);
                coverLetter.put(
                        "openingParagraph",
                        "I am applying for the " + jobTitle + " role.");
            }
            if (output.path("generationNotes") instanceof ObjectNode notes) {
                notes.put("tailoringSummary",
                        "Fixture-generated documents tailored to the selected vacancy.");
            }
            contextualiseEvidenceLedger(
                    output,
                    cv,
                    coverLetter,
                    evidence,
                    jobTitle,
                    companyName,
                    command.jsonSchema());
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
            List<EvidenceValue> evidence,
            String jobTitle,
            String companyName,
            JsonNode schema
    ) {
        if (!(output.path("claims") instanceof ArrayNode claims)) {
            return;
        }

        if (cv != null) {
            EvidenceValue cvEvidence = profileEvidence(evidence, "CV")
                    .orElseThrow(() -> new GenerationBoundaryException(
                            "The CV fixture request has no supported confirmed evidence."));
            cv.put("personalSummary",
                    "Evidence-backed professional targeting " + jobTitle
                            + " opportunities. " + cvEvidence.value());
            if (schemaHasProperty(schema, "personalSummaryClaim")) {
                ObjectNode personalSummaryClaim = output.putObject(
                        "personalSummaryClaim");
                personalSummaryClaim.put("claimId", "CLAIM-9003");
                personalSummaryClaim.put("disposition", "REWORDED");
                personalSummaryClaim.putArray("evidenceIds")
                        .add(cvEvidence.id())
                        .add("JOB.TITLE");
                personalSummaryClaim.put(
                        "contentPath",
                        "/cv/personalSummary");
                personalSummaryClaim.put("reviewText", "");
            }
            if (schema.at("/properties/cv/properties/projects").isObject()
                    && cv.path("projects").isEmpty()) {
                List<EvidenceValue> projectEvidence = evidence.stream()
                        .filter(item -> item.supports("CV"))
                        .filter(item -> "PROJECT".equals(item.category()))
                        .toList();
                if (projectEvidence.isEmpty()) {
                    addFallbackProject(cv, claims, evidence, cvEvidence);
                } else {
                    contextualiseCvProject(cv, claims, projectEvidence);
                }
            }
            setClaimEvidence(claims, "/cv/title", List.of("JOB.TITLE"));
            setClaimEvidence(claims, "/cv/targetRole", List.of("JOB.TITLE"));
            setClaimEvidence(
                    claims,
                    "/cv/personalSummary",
                    List.of(cvEvidence.id(), "JOB.TITLE"));
        }
        if (coverLetter == null) {
            return;
        }
        EvidenceValue coverLetterEvidence = profileEvidence(evidence, "COVER_LETTER")
                        .orElseThrow(() -> new GenerationBoundaryException(
                                "The cover-letter fixture request has no supported confirmed evidence."));
        if (schema.at(
                "/properties/coverLetter/properties/bodyParagraphs/items/type")
                .asText().equals("object")) {
            ArrayNode bodyParagraphs = coverLetter.putArray("bodyParagraphs");
            addSupportedParagraph(
                    bodyParagraphs,
                    "My evidence-backed experience includes: "
                            + coverLetterEvidence.value(),
                    coverLetterEvidence.id());
            addSupportedParagraph(
                    bodyParagraphs,
                    "This experience is directly relevant to the priorities of the "
                            + jobTitle + " role.",
                    coverLetterEvidence.id(),
                    "JOB.TITLE");
            addSupportedParagraph(
                    bodyParagraphs,
                    "I would bring this proven approach to " + companyName + ".",
                    coverLetterEvidence.id(),
                    "JOB.COMPANY");
            addSupportedParagraph(
                    bodyParagraphs,
                    "I welcome the opportunity to discuss how this background could contribute to your team.",
                    coverLetterEvidence.id());
        } else if (coverLetter.path("bodyParagraphs") instanceof ArrayNode bodyParagraphs
                && bodyParagraphs.size() >= 2) {
            bodyParagraphs.set(0, objectMapper.getNodeFactory().textNode(
                    "My evidence-backed experience includes: "
                            + coverLetterEvidence.value()));
            bodyParagraphs.set(1, objectMapper.getNodeFactory().textNode(
                    "I have reviewed the requirements for the " + jobTitle + " role."));
        }
        if (schemaHasProperty(schema, "canonicalApplicationClaims")) {
            coverLetter.put("greeting", "Dear Hiring Manager");
            coverLetter.put(
                    "openingParagraph",
                    "Please consider my application for this role.");
            coverLetter.put(
                    "closingParagraph",
                    "Thank you for considering my application.");
            ObjectNode canonicalClaims = output.putObject(
                    "canonicalApplicationClaims");
            addCanonicalApplicationClaim(
                    canonicalClaims.putObject("opening"),
                    "CLAIM-9001",
                    "/coverLetter/openingParagraph");
            addCanonicalApplicationClaim(
                    canonicalClaims.putObject("closing"),
                    "CLAIM-9002",
                    "/coverLetter/closingParagraph");
        } else {
            coverLetter.put(
                    "closingParagraph",
                    "Thank you for considering my application for the "
                            + jobTitle
                            + " role.");
        }

        setClaimEvidence(claims, "/coverLetter/title", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/jobTitle", List.of("JOB.TITLE"));
        setClaimEvidence(claims, "/coverLetter/companyName", List.of("JOB.COMPANY"));
        setClaimEvidence(
                claims,
                "/coverLetter/openingParagraph",
                List.of(
                        coverLetterEvidence.id(),
                        "REQUEST.GENERATION_INTENT",
                        "JOB.TITLE"));
        setClaimEvidence(
                claims,
                "/coverLetter/bodyParagraphs/0",
                List.of(coverLetterEvidence.id()));
        setClaimEvidence(
                claims,
                "/coverLetter/bodyParagraphs/1",
                List.of(coverLetterEvidence.id(), "JOB.TITLE"));
        setClaimEvidence(
                claims,
                "/coverLetter/closingParagraph",
                List.of(coverLetterEvidence.id(), "JOB.TITLE"));
    }

    private void addSupportedParagraph(
            ArrayNode paragraphs,
            String text,
            String... evidenceIds) {
        ObjectNode paragraph = paragraphs.addObject();
        paragraph.put("text", text);
        paragraph.put("disposition", "REWORDED");
        ArrayNode evidence = paragraph.putArray("evidenceIds");
        for (String evidenceId : evidenceIds) {
            evidence.add(evidenceId);
        }
    }

    private void addOrdinaryClaim(
            ArrayNode claims,
            String claimId,
            String evidenceId,
            String contentPath) {
        ObjectNode claim = claims.addObject();
        claim.put("claimId", claimId);
        claim.put("disposition", "REWORDED");
        claim.putArray("evidenceIds").add(evidenceId);
        claim.putArray("contentPaths").add(contentPath);
        claim.put("reviewText", "");
    }

    private void addFallbackProject(
            ObjectNode cv,
            ArrayNode claims,
            List<EvidenceValue> evidence,
            EvidenceValue cvEvidence) {
        EvidenceValue titleEvidence = evidence.stream()
                .filter(item -> item.supports("CV"))
                .filter(item -> "HEADING".equals(item.factType()))
                .findFirst()
                .orElse(cvEvidence);
        ObjectNode project = cv.putArray("projects").addObject();
        project.put(
                "title",
                titleEvidence.value().substring(
                        0,
                        Math.min(160, titleEvidence.value().length())));
        project.put("role", "");
        project.put("context", "");
        project.put("startDate", "");
        project.put("endDate", "");
        project.put("description", cvEvidence.value());
        project.putArray("highlights");
        addOrdinaryClaim(
                claims,
                "CLAIM-011",
                titleEvidence.id(),
                "/cv/projects/0/title");
        addOrdinaryClaim(
                claims,
                "CLAIM-012",
                cvEvidence.id(),
                "/cv/projects/0/description");
    }

    private void contextualiseCvProject(
            ObjectNode cv,
            ArrayNode claims,
            List<EvidenceValue> projectEvidence) {
        EvidenceValue title = requiredFact(projectEvidence, "HEADING");
        EvidenceValue role = optionalFact(projectEvidence, "PROJECT_ROLE").orElse(null);
        EvidenceValue description = requiredFact(projectEvidence, "DESCRIPTION");
        EvidenceValue startDate = optionalFact(projectEvidence, "START_DATE").orElse(null);
        EvidenceValue endDate = optionalFact(projectEvidence, "END_DATE").orElse(null);

        ObjectNode project = cv.putArray("projects").addObject();
        project.put("title", title.value());
        project.put("role", role == null ? "" : role.value());
        project.put("context", "");
        project.put("startDate", startDate == null ? "" : startDate.value());
        project.put("endDate", endDate == null ? "" : endDate.value());
        project.put("description", description.value());
        project.putArray("highlights");

        appendSupportedClaim(claims, "CLAIM-101", "/cv/projects/0/title", title.id());
        if (role != null) {
            appendSupportedClaim(claims, "CLAIM-102", "/cv/projects/0/role", role.id());
        }
        if (startDate != null) {
            appendSupportedClaim(
                    claims, "CLAIM-103", "/cv/projects/0/startDate", startDate.id());
        }
        if (endDate != null) {
            appendSupportedClaim(
                    claims, "CLAIM-104", "/cv/projects/0/endDate", endDate.id());
        }
        appendSupportedClaim(
                claims, "CLAIM-105", "/cv/projects/0/description", description.id());
    }

    private EvidenceValue requiredFact(
            List<EvidenceValue> evidence,
            String factType) {
        return optionalFact(evidence, factType)
                .orElseThrow(() -> new GenerationBoundaryException(
                        "The selected project fixture is missing " + factType + "."));
    }

    private Optional<EvidenceValue> optionalFact(
            List<EvidenceValue> evidence,
            String factType) {
        return evidence.stream()
                .filter(item -> factType.equals(item.factType()))
                .findFirst();
    }

    private void appendSupportedClaim(
            ArrayNode claims,
            String claimId,
            String contentPath,
            String evidenceId) {
        ObjectNode claim = claims.addObject();
        claim.put("claimId", claimId);
        claim.put("disposition", "SUPPORTED");
        claim.putArray("evidenceIds").add(evidenceId);
        claim.putArray("contentPaths").add(contentPath);
        claim.put("reviewText", "");
    }

    private void addCanonicalApplicationClaim(
            ObjectNode claim,
            String claimId,
            String contentPath) {
        claim.put("claimId", claimId);
        claim.put("disposition", "SUPPORTED");
        claim.put(
                "generationIntentEvidenceId",
                "REQUEST.GENERATION_INTENT");
        claim.put("jobTitleEvidenceId", "JOB.TITLE");
        claim.put("companyEvidenceId", "JOB.COMPANY");
        claim.put("contentPath", contentPath);
        claim.put("reviewText", "");
    }

    private boolean schemaHasProperty(JsonNode schema, String property) {
        return schema != null && schema.path("properties").has(property);
    }

    private RequestedOutputs requestedOutputs(JsonNode schema) {
        JsonNode properties = schema == null ? null : schema.path("properties");
        boolean cv = properties != null && properties.has("cv");
        boolean coverLetter = properties != null && properties.has("coverLetter");
        return cv || coverLetter
                ? new RequestedOutputs(cv, coverLetter)
                : new RequestedOutputs(true, true);
    }

    private void projectSelectedOutputs(
            ObjectNode output,
            RequestedOutputs requestedOutputs) {
        if (!requestedOutputs.cv()) {
            output.remove("cv");
            output.remove("personalSummaryClaim");
        }
        if (!requestedOutputs.coverLetter()) {
            output.remove("coverLetter");
            output.remove("canonicalApplicationClaims");
        }
        if (!(output.path("claims") instanceof ArrayNode claims)) {
            return;
        }
        for (int index = claims.size() - 1; index >= 0; index--) {
            JsonNode claim = claims.get(index);
            boolean targetsCv = claimTargets(claim, "/cv/");
            boolean targetsCoverLetter = claimTargets(claim, "/coverLetter/");
            if ((targetsCv && !requestedOutputs.cv())
                    || (targetsCoverLetter && !requestedOutputs.coverLetter())
                    || !allowedSelectedClaim(claim, requestedOutputs)) {
                claims.remove(index);
            }
        }
    }

    private boolean allowedSelectedClaim(
            JsonNode claim,
            RequestedOutputs requestedOutputs) {
        if (requestedOutputs.cv() && requestedOutputs.coverLetter()) {
            return true;
        }
        return StreamSupport.stream(
                        claim.path("contentPaths").spliterator(), false)
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .allMatch(path -> requestedOutputs.cv()
                        ? path.equals("/cv/targetRole")
                                || path.startsWith("/cv/projects/")
                                || path.startsWith("/cv/qualifications/")
                                || path.startsWith("/cv/workHistory/")
                        : path.equals("/coverLetter/title")
                                || path.equals("/coverLetter/jobTitle")
                                || path.equals("/coverLetter/companyName"));
    }

    private boolean claimTargets(JsonNode claim, String prefix) {
        return StreamSupport.stream(
                        claim.path("contentPaths").spliterator(), false)
                .anyMatch(path -> path.isTextual()
                        && path.textValue().startsWith(prefix));
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

    private record RequestedOutputs(boolean cv, boolean coverLetter) {
    }
}
