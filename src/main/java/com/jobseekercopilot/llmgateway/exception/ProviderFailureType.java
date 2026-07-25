package com.jobseekercopilot.llmgateway.exception;

public enum ProviderFailureType {
    AUTHENTICATION,
    RATE_LIMITED,
    QUOTA_EXHAUSTED,
    TIMEOUT,
    UNAVAILABLE,
    CAPACITY_EXHAUSTED,
    INVALID_RESPONSE,
    REQUEST_REJECTED,
    CIRCUIT_OPEN,
    CANCELLED
}
