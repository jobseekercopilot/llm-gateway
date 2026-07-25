package com.jobseekercopilot.llmgateway.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedProviderCallExecutorTest {

    @Test
    void enforcesOneHardCallerDeadlineAndCancelsTheWorker() {
        var worker = Executors.newSingleThreadExecutor();
        BoundedProviderCallExecutor executor = new BoundedProviderCallExecutor(
                worker,
                Duration.ofMillis(25)
        );

        ProviderFailureException exception = assertThrows(
                ProviderFailureException.class,
                () -> executor.execute(() -> {
                    TimeUnit.SECONDS.sleep(5);
                    throw new AssertionError("deadline failed");
                })
        );

        assertEquals(ProviderFailureType.TIMEOUT, exception.getType());
        executor.shutdown();
    }
}
