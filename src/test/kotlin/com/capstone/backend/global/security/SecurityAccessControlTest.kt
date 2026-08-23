package com.capstone.backend.global.security

import com.capstone.backend.global.util.JwtUtil
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Date

/**
 * 경로별 접근 통제와 필터 단계 인증 실패 응답을 검증한다.
 *
 * 여기서는 GlobalExceptionHandlerTest와 달리 실제 Spring 컨텍스트를 띄운다.
 * SecurityFilterChain·JwtAuthenticationFilter·AuthenticationEntryPoint는
 * DispatcherServlet 바깥에서 동작하므로 standaloneSetup으로는 재현할 수 없기 때문이다.
 * DB는 test 프로파일의 H2 인메모리를 쓴다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityAccessControlTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtUtil: JwtUtil

    private val testSecret = "test_secret_key_for_tests_only_do_not_use_in_production"

    // ── 보호된 경로 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("토큰 없이 분석 결과를 조회하면 401과 LOGIN_REQUIRED가 우리 형식으로 나간다")
    fun noToken_returns401InOurFormat() {
        mockMvc
            .perform(get("/api/analysis/1/result"))
            .andExpect(status().isUnauthorized())
            // Spring Security 기본 응답이 아니라 ErrorResponse 형식이어야 한다.
            .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
            .andExpect(jsonPath("$.path").value("/api/analysis/1/result"))
            .andExpect(jsonPath("$.timestamp").exists())
    }

    @Test
    @DisplayName("만료된 토큰은 EXPIRED_TOKEN으로 응답해 클라이언트가 갱신을 시도할 수 있게 한다")
    fun expiredToken_returnsExpiredCode() {
        val key = Keys.hmacShaKeyFor(testSecret.toByteArray())
        val expired =
            Jwts
                .builder()
                .subject("1")
                .issuedAt(Date(System.currentTimeMillis() - 10_000))
                .expiration(Date(System.currentTimeMillis() - 5_000))
                .signWith(key)
                .compact()

        mockMvc
            .perform(get("/api/users/me").header("Authorization", "Bearer $expired"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"))
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 INVALID_TOKEN으로 구분된다")
    fun forgedToken_returnsInvalidCode() {
        val attackerKey = Keys.hmacShaKeyFor("completely_different_secret_key_at_least_32_bytes".toByteArray())
        val forged =
            Jwts
                .builder()
                .subject("1")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(attackerKey)
                .compact()

        mockMvc
            .perform(get("/api/users/me").header("Authorization", "Bearer $forged"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @Test
    @DisplayName("서명은 유효하지만 subject가 숫자가 아닌 토큰은 500이 아니라 401이 된다")
    fun nonNumericSubject_returns401NotServerError() {
        val key = Keys.hmacShaKeyFor(testSecret.toByteArray())
        val weird =
            Jwts
                .builder()
                .subject("not-a-number")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact()

        mockMvc
            .perform(get("/api/users/me").header("Authorization", "Bearer $weird"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
    }

    @Test
    @DisplayName("유효한 토큰이면 인증 단계를 통과한다(401이 아니다)")
    fun validToken_passesAuthentication() {
        val token = jwtUtil.generateAccessToken(1L, "test@test.com")

        mockMvc
            .perform(get("/api/users/me").header("Authorization", "Bearer $token"))
            // 사용자가 DB에 없어 404가 나지만, 인증 자체는 통과했다는 것이 핵심이다.
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }

    // ── 공개 경로 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("인증 API는 토큰 없이도 접근할 수 있다")
    fun authEndpoint_isPublic() {
        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"not-an-email","nickname":"테스터"}"""),
            )
            // 401이 아니라 입력값 검증 실패(400)가 나와야 공개 경로라는 뜻이다.
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    // ── 내부 전용 경로 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("내부 API는 loopback에서 오면 허용된다(파이썬 분석 서버 경로)")
    fun internalApi_allowedFromLoopback() {
        mockMvc
            .perform(
                get("/api/internal/analysis/reference-models").with { request ->
                    request.remoteAddr = "127.0.0.1"
                    request
                },
            ).andExpect(status().isOk())
    }

    @Test
    @DisplayName("내부 API를 외부 IP에서 호출하면 403으로 차단된다")
    fun internalApi_blockedFromExternalIp() {
        mockMvc
            .perform(
                get("/api/internal/analysis/reference-models").with { request ->
                    request.remoteAddr = "203.0.113.5" // 문서화용 예약 대역(RFC 5737)
                    request
                },
            ).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    @DisplayName("레퍼런스 전체 삭제 API는 외부에서 호출할 수 없다")
    fun referenceClear_blockedFromExternalIp() {
        mockMvc
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete("/api/internal/analysis/reference-models/clear")
                    .with { request ->
                        request.remoteAddr = "203.0.113.5"
                        request
                    },
            ).andExpect(status().isForbidden())
    }
}
