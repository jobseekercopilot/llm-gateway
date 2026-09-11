package com.jobseekercopilot.llmgateway.resilience;

import com.jobseekercopilot.llmgateway.config.ProviderResilienceSettings;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureException;
import com.jobseekercopilot.llmgateway.exception.ProviderFailureType;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

@Component
public class BoundedProviderCallExecutor implements ProviderCallExecutor {
    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final ExecutorService executor;
    private final Duration callTimeout;

    @Autowired
    public BoundedProviderCallExecutor(ProviderResilienceSettings settings) {
        this(
                new ThreadPoolExecutor(
                        settings.maxConcurrentCalls(),
                        settings.maxConcurrentCalls(),
                        0L,
                        TimeUnit.MILLISECONDS,
                        new SynchronousQueue<>(),
                        providerThreadFactory(),
                        new ThreadPoolExecutor.AbortPolicy()
                ),
                Duration.ofMillis(settings.callTimeout())
        );
    }

    BoundedProviderCallExecutor(ExecutorService executor, Duration callTimeout) {
        this.executor = executor;
        this.callTimeout = callTimeout;
    }

    @Override
    public ResponseEntity<byte[]> execute(Callable<ResponseEntity<byte[]>> providerCall) {
        Future<ResponseEntity<byte[]>> future;
        try {
            future = executor.submit(providerCall);
        } catch (RuntimeException exception) {
            throw new ProviderFailureException(
                    ProviderFailureType.CAPACITY_EXHAUSTED,
                    "The bounded provider call pool has no available capacity.",
                    1L,
                    exception
            );
        }

        try {
            return future.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new ProviderFailureException(
                    ProviderFailureType.TIMEOUT,
                    "The provider call exceeded its configured deadline.",
                    null,
                    exception
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProviderFailureException(
                    ProviderFailureType.CANCELLED,
                    "The provider call was cancelled.",
                    null,
                    exception
            );
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new ProviderFailureException(
                    ProviderFailureType.UNAVAILABLE,
                    "The provider call failed.",
                    null,
                    cause
            );
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private static ThreadFactory providerThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "llm-provider-" + THREAD_SEQUENCE.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }
}
