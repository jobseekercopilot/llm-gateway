package com.jobseekercopilot.llmgateway.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.util.List;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException ex) {
        List<String> details = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.toList());

        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "VALIDATION_FAILED",
                "Request validation failed",
                LocalDateTime.now(),
                details
        );

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableRequest(HttpMessageNotReadableException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                "The request body is malformed or contains unsupported fields."
        );
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpClientError(HttpClientErrorException ex) {
        HttpStatus status = (HttpStatus) ex.getStatusCode();

        if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
            log.error("Provider authentication failed status={}", status.value());
            ErrorResponse errorResponse = new ErrorResponse(
                    status.value(),
                    "PROVIDER_AUTHENTICATION_FAILED",
                    "The configured generation adapter could not authenticate."
            );
            return ResponseEntity.status(status).body(errorResponse);
        }

        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            log.warn("Provider rate limit exceeded");
            ErrorResponse errorResponse = new ErrorResponse(
                    status.value(),
                    "PROVIDER_RATE_LIMITED",
                    "Generation capacity is temporarily unavailable. Please try again later."
            );
            return ResponseEntity.status(status).body(errorResponse);
        }

        log.error("Provider client error status={}", status.value());
        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                "PROVIDER_REQUEST_FAILED",
                "The generation adapter rejected the request."
        );
        return ResponseEntity.status(status).body(errorResponse);
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleHttpServerError(HttpServerErrorException ex) {
        log.error("Provider server error status={}", ex.getStatusCode().value());
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "PROVIDER_UNAVAILABLE",
                "Generation is temporarily unavailable. Please try again later."
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(ResourceAccessException ex) {
        log.error("Provider connection timed out");
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.GATEWAY_TIMEOUT.value(),
                "PROVIDER_TIMEOUT",
                "Generation did not complete in time. Please try again later."
        );
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(errorResponse);
    }

    @ExceptionHandler(ProviderDisabledException.class)
    public ResponseEntity<ErrorResponse> handleProviderDisabled(ProviderDisabledException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "GENERATION_DISABLED",
                "External generation is disabled for this deployment."
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorResponse);
    }

    @ExceptionHandler(GenerationRefusedException.class)
    public ResponseEntity<ErrorResponse> handleGenerationRefused(GenerationRefusedException ex) {
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.UNPROCESSABLE_ENTITY.value(),
                "GENERATION_REFUSED",
                "The request could not produce an approved generation result."
        );
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorResponse);
    }

    @ExceptionHandler(GenerationBoundaryException.class)
    public ResponseEntity<ErrorResponse> handleGenerationBoundary(GenerationBoundaryException ex) {
        log.error("Provider response violated the generation boundary: {}", ex.getMessage());
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.BAD_GATEWAY.value(),
                "INVALID_PROVIDER_RESPONSE",
                "The generation adapter returned an invalid response."
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorResponse);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorResponse> handleRuntimeException(RuntimeException ex) {
        log.error("Unexpected generation error type={}", ex.getClass().getSimpleName());
        ErrorResponse errorResponse = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_ERROR",
                "An unexpected error occurred. Please try again later."
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
