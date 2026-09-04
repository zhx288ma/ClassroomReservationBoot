package com.xuan.boot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    private static final String TOKEN_SCHEME = "X-Token";

    @Bean
    public OpenAPI classroomReservationOpenApi() {
        return new OpenAPI().info(new Info()
                .title("智慧教室预约与资源调度平台 API")
                .version("2.0.0")
                .description("Spring Boot + Redis + Redisson + RabbitMQ upgraded backend"))
                .components(new Components().addSecuritySchemes(TOKEN_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(TOKEN_SCHEME)))
                .addSecurityItem(new SecurityRequirement().addList(TOKEN_SCHEME));
    }
}
