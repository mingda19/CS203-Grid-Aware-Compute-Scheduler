package com.gacs.backend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Grid Aware Compute Scheduler (GACS) REST API")
                        .version("1.0.0")
                        .description("Comprehensive REST API contract for the Grid Aware Compute Scheduler platform. "
                                + "Provides endpoints for user registration, email OTP verification, JWT authentication with "
                                + "sliding-window Refresh Token rotation (Remember-Me), session lifecycle management, and role-based administration.")
                        .contact(new Contact()
                                .name("GACS Engineering Team")
                                .email("admin@datacenter.io"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")))
                .servers(List.of(
                        new Server().url("http://localhost:8080").description("Local Development Server")
                ))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .name("bearerAuth")
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Access Token authorization header using the Bearer scheme. Enter the access token obtained from /api/auth/login or /api/auth/refresh."))
                        .addSecuritySchemes("cookieAuth", new SecurityScheme()
                                .name("refreshToken")
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .description("HttpOnly secure cookie containing the persistent UUIDv4 refresh token (24-hour default or 14-day remember-me). Automatically sent with credentials: 'include'.")
                        )
                );
    }
}
