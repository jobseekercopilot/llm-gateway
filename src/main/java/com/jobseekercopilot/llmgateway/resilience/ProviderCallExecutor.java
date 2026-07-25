package com.jobseekercopilot.llmgateway.resilience;

import java.util.concurrent.Callable;
import org.springframework.http.ResponseEntity;

@FunctionalInterface
public interface ProviderCallExecutor {
    ResponseEntity<byte[]> execute(Callable<ResponseEntity<byte[]>> providerCall);
}
