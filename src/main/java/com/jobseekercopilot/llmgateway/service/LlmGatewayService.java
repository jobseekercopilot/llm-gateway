package com.jobseekercopilot.llmgateway.service;

import com.jobseekercopilot.llmgateway.client.LlmProviderClient;
import com.jobseekercopilot.llmgateway.client.OpenAiGenerationResult;
import com.jobseekercopilot.llmgateway.config.LlmConfiguration;
import com.jobseekercopilot.llmgateway.config.OpenAiConfiguration;
import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.dto.LlmUsage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmGatewayService {

    private final LlmProviderClient llmProviderClient;
    private final LlmConfiguration llmConfiguration;
    private final OpenAiConfiguration openAiConfiguration;

    public GenerateResponse generate(GenerateRequest request) {
        if (llmConfiguration.isMockMode()) {
            return handleMockMode(request);
        }

        return handleOpenAiGeneration(request);
    }

    private GenerateResponse handleOpenAiGeneration(GenerateRequest request) {
        long startedAt = System.nanoTime();
        log.info("LLM request started provider=OPENAI model={} taskType={} temperature={} maxTokens={}",
                openAiConfiguration.getModel(),
                request.getTaskType(),
                request.getTemperature(),
                request.getMaxTokens());

        OpenAiGenerationResult result = llmProviderClient.generate(request);
        LlmUsage usage = result.usage();
        log.info("LLM request completed provider=OPENAI model={} inputTokens={} outputTokens={} totalTokens={} durationMs={}",
                usage == null ? openAiConfiguration.getModel() : usage.getModel(),
                usage == null ? null : usage.getInputTokens(),
                usage == null ? null : usage.getOutputTokens(),
                usage == null ? null : usage.getTotalTokens(),
                (System.nanoTime() - startedAt) / 1_000_000);

        return GenerateResponse.builder()
                .provider(usage == null ? "OPENAI" : usage.getProvider())
                .model(usage == null ? openAiConfiguration.getModel() : usage.getModel())
                .usage(result.usage())
                .response(result.content())
                .build();
    }

    private GenerateResponse handleMockMode(GenerateRequest request) {
        log.info("Mock mode enabled. Returning mock response for taskType={}", request.getTaskType());

        return GenerateResponse.builder()
                .provider("MOCK")
                .model("mock-model")
                .usage(LlmUsage.builder()
                        .provider("MOCK")
                        .model("mock-model")
                        .inputTokens(1000L)
                        .outputTokens(2000L)
                        .totalTokens(3000L)
                        .build())
                .response("""
                        {
                          "cv": {
                            "title": "Tailored CV",
                            "targetRole": "Selected role",
                            "personalSummary": "A tailored professional summary generated in local mock mode.",
                            "coreSkills": [],
                            "qualifications": [],
                            "workHistory": []
                          },
                          "coverLetter": {
                            "title": "Tailored Cover Letter",
                            "jobTitle": "Selected role",
                            "companyName": "Selected company",
                            "greeting": "Dear Hiring Manager,",
                            "openingParagraph": "I am pleased to apply for this role.",
                            "bodyParagraphs": ["My profile and experience align with the opportunity."],
                            "closingParagraph": "Thank you for considering my application.",
                            "signOff": "Yours sincerely"
                          },
                          "generationNotes": {
                            "assumptionsMade": ["Local mock mode was used."],
                            "missingInformation": [],
                            "tailoringSummary": "Mock content for end-to-end local verification."
                          }
                        }
                        """)
                .build();
    }
}
