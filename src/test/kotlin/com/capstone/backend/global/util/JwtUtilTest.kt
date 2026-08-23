package com.capstone.backend.global.util

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Date

/**
 * 토큰 검증이 "만료"와 "위조"를 구분하는지, 조작된 토큰에서 예외로 터지지 않는지 확인한다.
 *
 * 이 구분이 없으면 클라이언트는 토큰 갱신으로 해결될 상황에도 재로그인을 시켜야 한다.
 */
class JwtUtilTest {
    private val secret = "test_secret_key_for_tests_only_do_not_use_in_production"
    private val jwtUtil = JwtUtil(secretString = secret, accessExp = 3_600_000, refreshExp = 1_209_600_000)

    @Test
    @DisplayName("정상 토큰은 VALID이고 subject에서 사용자 ID를 꺼낼 수 있다")
    fun validToken() {
        val token = jwtUtil.generateAccessToken(42L, "test@test.com")

        assertThat(jwtUtil.validateToken(token)).isEqualTo(TokenStatus.VALID)
        assertThat(jwtUtil.getIdFromToken(token)).isEqualTo(42L)
        assertThat(jwtUtil.getEmailFromToken(token)).isEqualTo("test@test.com")
    }

    @Test
    @DisplayName("만료된 토큰은 INVALID가 아니라 EXPIRED로 구분된다")
    fun expiredToken() {
        val key = Keys.hmacShaKeyFor(secret.toByteArray())
        val past = Date(System.currentTimeMillis() - 10_000)
        val expired =
            Jwts
                .builder()
                .subject("1")
                .issuedAt(past)
                .expiration(Date(System.currentTimeMillis() - 5_000))
                .signWith(key)
                .compact()

        assertThat(jwtUtil.validateToken(expired)).isEqualTo(TokenStatus.EXPIRED)
    }

    @Test
    @DisplayName("다른 키로 서명된 토큰은 INVALID다")
    fun forgedSignature() {
        val attackerKey = Keys.hmacShaKeyFor("completely_different_secret_key_at_least_32_bytes".toByteArray())
        val forged =
            Jwts
                .builder()
                .subject("1")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(attackerKey)
                .compact()

        assertThat(jwtUtil.validateToken(forged)).isEqualTo(TokenStatus.INVALID)
    }

    @Test
    @DisplayName("형식이 깨진 문자열이나 빈 문자열도 예외 없이 INVALID로 처리된다")
    fun malformedToken() {
        assertThat(jwtUtil.validateToken("not.a.jwt")).isEqualTo(TokenStatus.INVALID)
        assertThat(jwtUtil.validateToken("")).isEqualTo(TokenStatus.INVALID)
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 예외 대신 null을 반환한다")
    fun nonNumericSubject_returnsNull() {
        // 서명은 우리 키로 유효하지만 subject가 숫자가 아닌 토큰.
        // 예전 구현은 toLong()을 써서 NumberFormatException이 필터 안에서 터졌고,
        // @RestControllerAdvice가 필터 예외를 못 잡아 500이 됐다.
        val key = Keys.hmacShaKeyFor(secret.toByteArray())
        val weird =
            Jwts
                .builder()
                .subject("not-a-number")
                .expiration(Date(System.currentTimeMillis() + 60_000))
                .signWith(key)
                .compact()

        assertThat(jwtUtil.validateToken(weird)).isEqualTo(TokenStatus.VALID)
        assertThat(jwtUtil.getIdFromToken(weird)).isNull()
    }
}
