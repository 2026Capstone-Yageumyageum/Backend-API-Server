package com.capstone.backend.global.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class SwaggerConfig {

    @Bean
    fun openAPI(): OpenAPI {
        // 1. JWT 토큰을 입력받을 수 있는 보안 스키마 설정 (Bearer 방식)
        val securityScheme = SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT")
            .`in`(SecurityScheme.In.HEADER)
            .name("Authorization")

        // 2. 모든 API에 이 보안 스키마를 적용하겠다는 요구사항 설정
        val securityRequirement = SecurityRequirement().addList("bearerAuth")

        return OpenAPI()
            .info(
                Info().title("야구 투구폼 교정 앱 API")
                    .description("캡스톤 프로젝트 백엔드 API 명세서 및 테스트")
                    .version("v1.0.0")
            )
            .components(Components().addSecuritySchemes("bearerAuth", securityScheme))
            .security(listOf(securityRequirement))
    }
}