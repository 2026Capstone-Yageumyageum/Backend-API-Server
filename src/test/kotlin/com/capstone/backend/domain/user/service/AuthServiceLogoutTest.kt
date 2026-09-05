package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.user.repository.RefreshTokenRepository
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.global.util.JwtUtil
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.concurrent.TimeUnit

/**
 * 로그아웃이 서버에 저장된 리프레시 토큰을 실제로 지우는지 검증한다.
 *
 * 왜 Spring 컨텍스트를 띄우지 않는가:
 * RefreshTokenRepository는 Redis 저장소다. 컨텍스트를 띄우면 테스트가 Redis 연결을
 * 요구하게 되고, 검증하려는 것("지웠는가")과 무관한 이유로 실패한다.
 * 저장소를 mock으로 두면 로그아웃 판단 로직만 정확히 겨냥할 수 있다.
 *
 * JwtUtil은 mock하지 않는다. "서명이 유효한 토큰만 지운다"가 검증 대상이므로
 * 진짜 서명/검증이 동작해야 의미가 있다.
 */
class AuthServiceLogoutTest {
    // HMAC-SHA 키는 32바이트 이상이어야 한다. 테스트 설정(application.yaml)과 같은 값을 쓴다.
    private val jwtUtil =
        JwtUtil(
            secretString = "test_secret_key_for_tests_only_do_not_use_in_production",
            accessExp = TimeUnit.HOURS.toMillis(1),
            refreshExp = TimeUnit.DAYS.toMillis(14),
        )

    private val refreshTokenRepository = mock(RefreshTokenRepository::class.java)
    private val userRepository = mock(UserRepository::class.java)

    private val authService =
        AuthService(
            userRepository = userRepository,
            googleClientId = "test-client-id",
            jwtUtil = jwtUtil,
            refreshTokenRepository = refreshTokenRepository,
        )

    @Test
    @DisplayName("유효한 리프레시 토큰으로 로그아웃하면 서버에 저장된 토큰을 지운다")
    fun validToken_deletesStoredToken() {
        val refreshToken = jwtUtil.generateRefreshToken(1L)

        authService.logout(refreshToken)

        // 이게 로그아웃의 전부다. 이 삭제가 없으면 클라이언트가 자기 토큰을 지워도
        // 서버는 그 토큰을 계속 유효하다고 보고, 값을 가진 누구든 갱신을 이어갈 수 있다.
        verify(refreshTokenRepository).deleteById(refreshToken)
    }

    @Test
    @DisplayName("이미 지워진 토큰으로 다시 로그아웃해도 예외 없이 통과한다")
    fun alreadyDeletedToken_doesNotThrow() {
        val refreshToken = jwtUtil.generateRefreshToken(1L)

        // 로그아웃은 멱등해야 한다. 앱이 재시도하거나 두 화면에서 동시에 호출해도
        // 실패로 보이면 안 된다(사용자는 이미 로그아웃할 결심을 했다).
        authService.logout(refreshToken)
        authService.logout(refreshToken)
    }

    @Test
    @DisplayName("서명이 위조된 토큰은 삭제를 시도하지 않는다")
    fun forgedToken_isIgnored() {
        val forged =
            JwtUtil(
                secretString = "another_secret_key_that_is_long_enough_for_hmac_sha",
                accessExp = TimeUnit.HOURS.toMillis(1),
                refreshExp = TimeUnit.DAYS.toMillis(14),
            ).generateRefreshToken(1L)

        authService.logout(forged)

        // 아무 문자열이나 받아 저장소 삭제를 시도하게 두지 않는다.
        verify(refreshTokenRepository, never()).deleteById(forged)
    }

    @Test
    @DisplayName("만료된 토큰은 삭제를 시도하지 않는다")
    fun expiredToken_isIgnored() {
        // 리프레시 토큰의 Redis TTL은 JWT 만료와 같은 시각에 끝나도록 저장된다.
        // 따라서 JWT가 만료됐다면 저장소 항목도 이미 사라졌고, 지울 것이 없다.
        val expiredJwtUtil =
            JwtUtil(
                secretString = "test_secret_key_for_tests_only_do_not_use_in_production",
                accessExp = -1L,
                refreshExp = -1L,
            )
        val expired = expiredJwtUtil.generateRefreshToken(1L)

        authService.logout(expired)

        verify(refreshTokenRepository, never()).deleteById(expired)
    }
}
