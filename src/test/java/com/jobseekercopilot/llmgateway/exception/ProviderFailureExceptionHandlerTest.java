package com.jobseekercopilot.llmgateway.exception;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class ProviderFailureExceptionHandlerTest {

    @Test
    void mapsProviderFailuresToStableProviderNeutralErrors() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new FailureController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        mockMvc.perform(get("/test/provider-failure"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "17"))
                .andExpect(jsonPath("$.error", is("PROVIDER_RATE_LIMITED")))
                .andExpect(jsonPath("$.message", is(
                        "Generation capacity is temporarily unavailable. Please try again later."
                )));
    }

    @RestController
    private static class FailureController {
        @GetMapping("/test/provider-failure")
        void fail() {
            throw new ProviderFailureException(
                    ProviderFailureType.RATE_LIMITED,
                    "sensitive provider message",
                    17L
            );
        }
    }
}
