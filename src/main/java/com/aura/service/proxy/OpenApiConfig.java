package com.aura.service.proxy;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI auraMathProxyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AuraService — AuraMath Proxy")
                        .version("1.0.0")
                        .description("Thin wrapper exposing /v1/** routes that forward to the upstream AuraMath service."))
                .servers(List.of(new Server().url("/").description("This service")));
    }
}
