package com.jobseekercopilot.llmgateway.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
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
                            (first, ignored) -> first));
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
            return objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException exception) {
            throw new GenerationBoundaryException(
                    "The CV and cover-letter fixture payload could not be processed.");
        }
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
}
