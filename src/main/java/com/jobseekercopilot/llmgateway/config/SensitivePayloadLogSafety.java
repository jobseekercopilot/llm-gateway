package com.jobseekercopilot.llmgateway.config;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.stereotype.Component;

@Component
public class SensitivePayloadLogSafety implements ApplicationRunner {
    static final List<String> PAYLOAD_LOGGERS = List.of(
            "org.springframework.web.HttpLogging",
            "org.springframework.web.client.RestTemplate",
            "org.springframework.web.method.HandlerMethod",
            "org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver",
            "org.springframework.web.servlet.mvc.method.annotation.HttpEntityMethodProcessor",
            "org.springframework.web.servlet.mvc.method.annotation.RequestResponseBodyMethodProcessor"
    );

    @Override
    public void run(ApplicationArguments args) {
        validate(LoggingSystem.get(getClass().getClassLoader()));
    }

    static void validate(LoggingSystem loggingSystem) {
        for (String loggerName : PAYLOAD_LOGGERS) {
            LoggerConfiguration configuration = loggingSystem.getLoggerConfiguration(loggerName);
            LogLevel effectiveLevel = configuration == null ? null : configuration.getEffectiveLevel();
            if (effectiveLevel == null || effectiveLevel == LogLevel.DEBUG || effectiveLevel == LogLevel.TRACE) {
                throw new IllegalStateException(
                        loggerName + " must remain above DEBUG to protect model-bound and rejected request values.");
            }
        }
    }
}
