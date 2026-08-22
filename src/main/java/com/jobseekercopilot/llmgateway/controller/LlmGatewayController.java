package com.jobseekercopilot.llmgateway.controller;

import com.jobseekercopilot.llmgateway.dto.GenerateRequest;
import com.jobseekercopilot.llmgateway.dto.GenerateResponse;
import com.jobseekercopilot.llmgateway.dto.GenerationRequest;
import com.jobseekercopilot.llmgateway.dto.GenerationResponse;
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
@RequestMapping("/api")
@RequiredArgsConstructor
@Tag(name = "Model Generation", description = "Provider-neutral bounded generation endpoints")
public class LlmGatewayController {

    private final LlmGatewayService llmGatewayService;

    @PostMapping("/v2/generations")
    @Operation(
            operationId = "generateV2",
            summary = "Generate bounded content through the configured adapter",
            description = """
                    Accepts a domain task, separately delimited trusted instructions and untrusted
                    input, an explicit output contract, and hard generation limits. Provider names,
                    request structures and credentials are not exposed to the caller.
                    """
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Content generated successfully",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = GenerationResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed request, unsupported fields or validation failure",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Configured adapter authentication failed",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "403",
                    description = "Configured adapter authentication was forbidden",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "422",
                    description = "Generation was refused or filtered",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "429",
                    description = "Configured adapter rate limited the request",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "Unexpected internal failure",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "Configured adapter returned an invalid response",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Generation is disabled or the configured adapter is unavailable",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            ),
            @ApiResponse(
                    responseCode = "504",
                    description = "Configured adapter did not respond in time",
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)
                    )
            )
    })
    public ResponseEntity<GenerationResponse> generate(@Valid @RequestBody GenerationRequest request) {
        log.debug("Received v2 generation request task={} format={}",
                request.getTask(), request.getOutput().getFormat());
        return ResponseEntity.ok(llmGatewayService.generate(request));
    }

    @Deprecated(forRemoval = false)
    @PostMapping("/v1/generate")
    @Operation(
            operationId = "generate",
            summary = "Generate content from a deprecated raw prompt",
            description = """
                    Transitional compatibility endpoint for the current CV consumer. New consumers
                    must use /api/v2/generations. This endpoint will be removed after consumer
                    migration because it cannot express a trusted/untrusted input boundary.
                    """,
            deprecated = true
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
                                              "error": "VALIDATION_FAILED",
                                              "message": "Request validation failed",
                                              "timestamp": "2026-06-21T12:00:00",
                                              "details": ["prompt: prompt is required and cannot be empty"]
                                            }
                                            """
                            )
                    )
            ),
            @ApiResponse(responseCode = "401", description = "Provider authentication failure"),
            @ApiResponse(
                    responseCode = "503",
                    description = "Generation adapter disabled or unavailable"
            ),
            @ApiResponse(
                    responseCode = "504",
                    description = "Generation adapter did not respond in time"
            )
    })
    public ResponseEntity<GenerateResponse> generateLegacy(@Valid @RequestBody GenerateRequest request) {
        log.debug("Received deprecated v1 generation request taskType={}", request.getTaskType());
        GenerateResponse response = llmGatewayService.generateLegacy(request);
        return ResponseEntity.status(HttpStatus.OK).body(response);
    }
}
