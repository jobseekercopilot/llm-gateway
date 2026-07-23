package com.jobseekercopilot.llmgateway.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
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
                                Single integration point between Jobseeker Copilot and OpenAI.
                                
                                This gateway:
                                - Accepts a fully built prompt
                                - Sends it to OpenAI
                                - Returns generated content
                                
                                This gateway does NOT:
                                - Build prompts
                                - Know about CVs, cover letters, jobs, benefits, or UC reports
                                - Make decisions based on taskType
                                """)
                        .version("1.0.0")
                        .contact(new Contact()
                                .name("Jobseeker Copilot"))
                        .license(new License()
                                .name("MIT")));
    }
}