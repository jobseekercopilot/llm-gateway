package com.jobseekercopilot.llmgateway.exception;

public class ProviderFailureException extends RuntimeException {
    private final ProviderFailureType type;
    private final Long retryAfterSeconds;

    public ProviderFailureException(ProviderFailureType type, String message) {
        this(type, message, null, null);
    }

    public ProviderFailureException(
            ProviderFailureType type,
            String message,
            Long retryAfterSeconds
    ) {
        this(type, message, retryAfterSeconds, null);
    }

    public ProviderFailureException(
            ProviderFailureType type,
            String message,
            Long retryAfterSeconds,
            Throwable cause
    ) {
        super(message, cause);
        this.type = type;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ProviderFailureType getType() {
        return type;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
