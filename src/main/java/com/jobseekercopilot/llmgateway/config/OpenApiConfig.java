package com.jobseekercopilot.llmgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI llmGatewayOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Jobseeker Copilot - LLM Gateway API")
                        .description("""
                                Provider-neutral generation boundary for Job Seeker Copilot.
                                
                                This gateway:
                                - Separates trusted task instructions from untrusted source data
                                - Requires an explicit output contract and bounded limits
                                - Hides adapter request structures and credentials
                                - Supports fail-closed DISABLED, deterministic FIXTURE and explicit LIVE modes
                                
                                The deprecated v1 raw-prompt endpoint remains only for a bounded consumer migration.
                                """)
                        .version("2.0.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("MIT")));
    }

    @Bean
    OpenApiCustomizer closeProviderNeutralSchemas() {
        return openApi -> List.of(
                        "GenerationRequest",
                        "GenerationOutputContract",
                        "GenerationLimits",
                        "GenerationResponse",
                        "GenerationUsage",
                        "GenerationAudit",
                        "ErrorResponse"
                ).forEach(name -> {
                    var schema = openApi.getComponents().getSchemas().get(name);
                    if (schema == null) {
                        throw new IllegalStateException("Expected OpenAPI schema is missing: " + name);
                    }
                    schema.setAdditionalProperties(false);
                });
    }
}
