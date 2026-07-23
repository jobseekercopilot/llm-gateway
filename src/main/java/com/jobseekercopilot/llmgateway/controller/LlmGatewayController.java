package com.jobseekercopilot.llmgateway.controller;

import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.exception.ErrorResponse;
import com.jobseekercopilot.llmgateway.service.LlmGatewayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "LLM Generation", description = "Endpoints for generating content via OpenAI")
public class LlmGatewayController {

    private final LlmGatewayService llmGatewayService;

    @PostMapping("/generate")
    @Operation(
            summary = "Generate content via OpenAI",
            description = """
                    Accepts a fully built prompt and sends it to OpenAI for generation.
                    
                    The gateway does NOT build prompts, nor does it know about CVs, cover letters, 
                    jobs, benefits, or UC reports. The taskType field is informational only.
                    
                    The response may contain JSON text returned by OpenAI.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content generated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenerateResponse.class),
                            examples = @ExampleObject(
                                    name = "successfulGeneration",
                                    summary = "Successful generation response",
                                    value = """
                                            {
                                              "provider": "OPENAI",
                                              "model": "gpt-4.1-mini",
                                              "usage": {
                                                "provider": "OPENAI",
                                                "model": "gpt-4.1-mini",
                                                "inputTokens": 4200,
                                                "outputTokens": 3100,
                                                "totalTokens": 7300
                                              },
                                              "response": "{\\\"message\\\":\\\"Generated content from OpenAI\\\"}"
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Validation error - missing or invalid fields",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class),
                            examples = @ExampleObject(
                                    name = "validationError",
                                    summary = "Validation error",
                                    value = """
                                            {
                                              "status": 400,
                                              "error": "Validation Failed",
                                              "message": "Request validation failed",
                                              "timestamp": "2026-06-21T12:00:00",
                                              "details": ["prompt: prompt is required and cannot be empty"]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "OpenAI authentication failure - invalid API key"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "OpenAI server error or service unavailable"
            ),
            @ApiResponse(
                    responseCode = "504",
                    description = "OpenAI did not respond in time"
            )
    })
    public ResponseEntity<GenerateResponse> generate(@Valid @RequestBody GenerateRequest request) {
        log.debug("Received generate request: taskType={}", request.getTaskType());
        GenerateResponse response = llmGatewayService.generate(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
