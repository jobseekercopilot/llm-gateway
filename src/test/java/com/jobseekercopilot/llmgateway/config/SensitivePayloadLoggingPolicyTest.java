package com.jobseekercopilot.llmgateway.config;

import ch.qos.logback.classic.LoggerContext;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerConfiguration;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest
class SensitivePayloadLoggingPolicyTest {

    @Test
    void frameworkPayloadLoggersRemainAboveDebug() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();

        for (String loggerName : SensitivePayloadLogSafety.PAYLOAD_LOGGERS) {
            assertFalse(context.getLogger(loggerName).isDebugEnabled(),
                    () -> loggerName + " must not emit model-bound or rejected request values at DEBUG");
        }
    }

    @Test
    void startupGuardAcceptsEffectiveInfoLevel() {
        LoggingSystem loggingSystem = loggingSystemWithEffectiveLevel(LogLevel.INFO);

        assertDoesNotThrow(() -> SensitivePayloadLogSafety.validate(loggingSystem));
    }

    @Test
    void startupGuardRejectsEffectiveDebugLevel() {
        LoggingSystem loggingSystem = loggingSystemWithEffectiveLevel(LogLevel.DEBUG);

        assertThrows(IllegalStateException.class, () -> SensitivePayloadLogSafety.validate(loggingSystem));
    }

    private LoggingSystem loggingSystemWithEffectiveLevel(LogLevel level) {
        LoggingSystem loggingSystem = mock(LoggingSystem.class);
        for (String loggerName : SensitivePayloadLogSafety.PAYLOAD_LOGGERS) {
            when(loggingSystem.getLoggerConfiguration(loggerName))
                    .thenReturn(new LoggerConfiguration(loggerName, level, level));
        }
        return loggingSystem;
    }
}
