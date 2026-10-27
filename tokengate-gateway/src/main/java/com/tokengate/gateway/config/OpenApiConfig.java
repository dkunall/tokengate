package com.tokengate.gateway.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI tokenGateApi() {
        return new OpenAPI()
                .info(new Info().title("TokenGate").version("0.1.0")
                        .description("OpenAI-compatible LLM gateway: routing, caching, limits, PII masking, metering")
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").description("Tenant API key, tg_<prefix>_<secret>"))
                        .addSecuritySchemes("adminJwt", new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")
                                .description("Admin JWT with a roles claim: ADMIN, BILLING or VIEWER")));
    }
}
