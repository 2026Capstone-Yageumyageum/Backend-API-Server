package com.capstone.backend.global.exception

import com.capstone.backend.domain.analysis.contoller.AnalysisController
import com.capstone.backend.domain.analysis.service.AnalysisService
import com.capstone.backend.domain.user.controller.AuthController
import com.capstone.backend.domain.user.controller.UserController
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.service.AuthService
import com.capstone.backend.domain.user.service.UserService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.mock
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * 예외가 의도한 HTTP 상태 코드와 [ErrorResponse] 형식으로 변환되는지 검증한다.
 *
 * standaloneSetup을 쓰는 이유:
 * 전체 Spring 컨텍스트를 띄우면 PostgreSQL·Redis·파이썬 분석 서버가 필요해진다.
 * 여기서 확인하려는 것은 "예외 → 응답 변환"뿐이므로, 컨트롤러와 예외 핸들러만 올리고
 * 서비스는 목으로 대체한다. 덕분에 외부 의존 없이 수 초 안에 끝난다.
 */
class GlobalExceptionHandlerTest {
    private val authService = mock(AuthService::class.java)
    private val userService = mock(UserService::class.java)
    private val analysisService = mock(AnalysisService::class.java)

    private val mockMvc: MockMvc =
        MockMvcBuilders
            .standaloneSetup(
                AuthController(authService),
                UserController(userService),
                AnalysisController(analysisService),
            ).setControllerAdvice(GlobalExceptionHandler())
            // @AuthenticationPrincipal을 해석하려면 이 리졸버가 필요하다.
            // SecurityContext가 비어 있으면 null을 넘겨주므로 "비로그인" 상황을 그대로 재현한다.
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    /** 로그인한 사용자를 흉내 낸다. JwtAuthenticationFilter가 하는 일과 같은 형태로 principal에 userId를 넣는다. */
    private fun authenticateAs(userId: Long) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
    }

    // ── 인증 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("비로그인 요청은 500이 아니라 401과 LOGIN_REQUIRED를 반환한다")
    fun unauthenticated_returns401() {
        mockMvc
            .perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("LOGIN_REQUIRED"))
            .andExpect(jsonPath("$.message").value("로그인이 필요합니다."))
            .andExpect(jsonPath("$.path").value("/api/users/me"))
    }

    // ── 입력값 검증 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("이메일 형식이 틀리면 400과 함께 어느 필드가 문제인지 알려준다")
    fun invalidEmail_returns400WithFieldErrors() {
        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"not-an-email","nickname":"테스터"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("email"))
            .andExpect(jsonPath("$.fieldErrors[0].message").value("올바른 이메일 형식이 아닙니다."))
    }

    @Test
    @DisplayName("닉네임이 최소 길이보다 짧으면 400을 반환한다")
    fun tooShortNickname_returns400() {
        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"test@test.com","nickname":"a"}"""),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.fieldErrors[0].field").value("nickname"))
    }

    @Test
    @DisplayName("본문 JSON이 깨졌으면 400과 INVALID_JSON_FORMAT을 반환한다")
    fun malformedJson_returns400() {
        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email": """),
            ).andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_JSON_FORMAT"))
    }

    @Test
    @DisplayName("필수 쿼리 파라미터가 없으면 400과 MISSING_PARAMETER를 반환한다")
    fun missingRequestParam_returns400() {
        authenticateAs(1L)
        mockMvc
            .perform(get("/api/users/me/growth")) // proId 누락
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MISSING_PARAMETER"))
    }

    @Test
    @DisplayName("경로 변수 타입이 맞지 않으면 400을 반환한다")
    fun pathVariableTypeMismatch_returns400() {
        mockMvc
            .perform(get("/api/analysis/abc/result")) // videoId 자리에 숫자가 아닌 값
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    // ── BusinessException이 ErrorCode의 상태 코드로 변환되는지 ─────────────────

    @Test
    @DisplayName("중복 가입은 500이 아니라 409와 DUPLICATE_EMAIL을 반환한다")
    fun duplicateEmail_returns409() {
        // SignupRequest는 data class라 equals가 값 기준으로 동작한다.
        // 덕분에 mockito-kotlin의 any() 없이도 인자를 그대로 적어 매칭할 수 있다.
        given(authService.signup(SignupRequest(email = "test@test.com", nickname = "테스터")))
            .willThrow(BusinessException(ErrorCode.DUPLICATE_EMAIL))

        mockMvc
            .perform(
                post("/api/auth/signup")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"test@test.com","nickname":"테스터"}"""),
            ).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"))
    }

    @Test
    @DisplayName("영상을 찾을 수 없으면 404와 VIDEO_NOT_FOUND를 반환한다")
    fun videoNotFound_returns404() {
        given(analysisService.getAnalysisResult(999L))
            .willThrow(BusinessException(ErrorCode.VIDEO_NOT_FOUND))

        mockMvc
            .perform(get("/api/analysis/999/result"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("영상을 찾을 수 없습니다."))
    }

    @Test
    @DisplayName("사용자를 찾을 수 없으면 404와 USER_NOT_FOUND를 반환한다")
    fun userNotFound_returns404() {
        authenticateAs(42L)
        given(userService.getProfile(42L))
            .willThrow(BusinessException(ErrorCode.USER_NOT_FOUND))

        mockMvc
            .perform(get("/api/users/me"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }

    @Test
    @DisplayName("분석 서버 오류는 500이 아니라 502를 반환한다")
    fun analysisServerFailure_returns502() {
        given(analysisService.getAnalysisResult(1L))
            .willThrow(BusinessException(ErrorCode.ANALYSIS_FAILED))

        mockMvc
            .perform(get("/api/analysis/1/result"))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.code").value("ANALYSIS_FAILED"))
    }

    // ── 정보 노출 차단 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("예상 못 한 예외는 500을 반환하되 내부 메시지를 응답에 노출하지 않는다")
    fun unexpectedException_hidesInternalDetails() {
        val leakyMessage =
            "Cannot invoke \"com.capstone.backend.domain.video.entity.SkeletonData.getSkeletonData()\" because it is null"
        given(analysisService.getAnalysisResult(7L))
            .willThrow(NullPointerException(leakyMessage))

        mockMvc
            .perform(get("/api/analysis/7/result"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            // 내부 클래스·메서드명이 담긴 원본 메시지 대신 일반 문구가 나가야 한다.
            .andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다."))
    }

    // ── 응답 형식의 일관성 ────────────────────────────────────────────────────

    @Test
    @DisplayName("검증 실패가 아닌 응답에는 fieldErrors 필드가 아예 포함되지 않는다")
    fun nonValidationError_omitsFieldErrors() {
        mockMvc
            .perform(get("/api/users/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.fieldErrors").doesNotExist())
            .andExpect(jsonPath("$.timestamp").exists())
    }
}
