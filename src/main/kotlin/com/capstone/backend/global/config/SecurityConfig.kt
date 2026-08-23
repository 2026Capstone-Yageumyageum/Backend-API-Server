package com.capstone.backend.global.config

import com.capstone.backend.global.filter.JwtAuthenticationFilter
import com.capstone.backend.global.security.JsonAccessDeniedHandler
import com.capstone.backend.global.security.JwtAuthenticationEntryPoint
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.authorization.AuthorizationManager
import org.springframework.security.authorization.AuthorizationManagers
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.IpAddressAuthorizationManager
import org.springframework.security.web.access.intercept.RequestAuthorizationContext
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val authenticationEntryPoint: JwtAuthenticationEntryPoint,
    private val accessDeniedHandler: JsonAccessDeniedHandler,
    // 파이썬 분석 서버가 인증 헤더 없이 /api/internal 을 호출하므로 토큰 대신 출발지 IP로 제한한다.
    // 배포 형태가 바뀌면(도커 브리지, 사설망 분리 등) 코드 수정 없이 설정만 바꾸면 되도록 뺐다.
    @Value("\${app.internal-api.allowed-cidrs}") private val internalAllowedCidrs: List<String>,
    @Value("\${app.cors.allowed-origin-patterns}") private val corsAllowedOriginPatterns: List<String>,
) {
    /**
     * 내부 전용 API 체인. 레퍼런스 모델 등록·삭제가 포함되어 있어
     * 외부에 열어두면 요청 한 번으로 프로 비교 데이터를 전부 지울 수 있다.
     *
     * 별도 체인으로 분리한 이유:
     * 이 경로는 JWT를 쓰지 않고 출발지 IP만 본다. 인증 개념이 없으므로 JWT 필터도 태우지 않는다.
     * 또 거부 시 항상 403이 나가야 한다. 하나의 체인에 두면 익명 요청이 거부될 때
     * Spring Security가 AuthenticationEntryPoint를 호출해 401("로그인하면 될 것 같은")을
     * 내보내는데, IP 제한은 토큰을 가져와도 결과가 같으므로 잘못된 신호다.
     */
    @Bean
    @Order(1)
    fun internalApiFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/api/internal/**")
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().access(internalNetworkOnly()) }
            .exceptionHandling {
                // 인증 여부와 무관하게 거부는 곧 "권한 없음"이므로 양쪽 모두 403 응답기로 붙인다.
                it.authenticationEntryPoint(accessDeniedHandler)
                it.accessDeniedHandler(accessDeniedHandler)
            }
        return http.build()
    }

    /** 앱·웹 클라이언트가 쓰는 일반 API 체인. */
    @Bean
    @Order(2)
    fun apiFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // 인증 없이 접근해야 하는 것만 남긴다.
                    .requestMatchers(
                        "/error",
                        "/api/auth/**",
                        "/swagger-ui/**",
                        "/v3/api-docs/**",
                        "/swagger-resources/**",
                        "/webjars/**",
                    ).permitAll()
                    // 나머지는 전부 인증 필요. 특히 /api/analysis/** 는 이전까지 permitAll이라
                    // videoId만 바꿔가며 남의 분석 결과를 조회할 수 있었다.
                    .anyRequest()
                    .authenticated()
            }.exceptionHandling {
                // 필터 단계 실패도 ErrorResponse 형식으로 응답하게 만든다.
                it.authenticationEntryPoint(authenticationEntryPoint)
                it.accessDeniedHandler(accessDeniedHandler)
            }.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /** 설정에 나열된 대역 중 하나에서 온 요청만 허용한다. */
    private fun internalNetworkOnly(): AuthorizationManager<RequestAuthorizationContext> =
        AuthorizationManagers.anyOf(
            *internalAllowedCidrs
                .map { IpAddressAuthorizationManager.hasIpAddress(it.trim()) }
                .toTypedArray(),
        )

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val configuration = CorsConfiguration()

        // allowCredentials=true 와 함께 쓰므로 와일드카드는 위험하다.
        // 허용할 출처를 설정으로 명시한다.
        configuration.allowedOriginPatterns = corsAllowedOriginPatterns
        configuration.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        configuration.allowedHeaders = listOf("*")
        configuration.allowCredentials = true

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", configuration)
        return source
    }
}
