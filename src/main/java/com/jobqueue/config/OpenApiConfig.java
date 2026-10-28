package com.jobqueue.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI jobQueueOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Distributed Job Queue API")
                .description("Submit, inspect, cancel, and review background jobs.")
                .version("1.0.0"));
    }
}
