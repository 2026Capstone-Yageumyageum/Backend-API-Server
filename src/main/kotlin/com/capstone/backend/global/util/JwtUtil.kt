package com.capstone.backend.global.util

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Date
import javax.crypto.SecretKey

/**
 * 토큰 검증 결과.
 *
 * 기존에는 validateToken이 Boolean만 돌려주어 "만료"와 "위조"를 구분할 수 없었다.
 * 클라이언트 입장에서 만료는 토큰 갱신(/api/auth/refresh)으로, 위조는 재로그인으로
 * 대응해야 하므로 서버가 이 둘을 나눠서 알려줘야 한다.
 */
enum class TokenStatus {
    VALID,
    EXPIRED,
    INVALID,
}

@Component
class JwtUtil(
    @Value("\${jwt.secret}") private val secretString: String,
    @Value("\${jwt.access-expiration}") private val accessExp: Long,
    @Value("\${jwt.refresh-expiration}") val refreshExp: Long,
) {
    private val secretKey: SecretKey = Keys.hmacShaKeyFor(secretString.toByteArray())

    fun generateAccessToken(
        id: Long,
        email: String,
    ): String {
        val now = Date()
        val validity = Date(now.time + accessExp)

        return Jwts
            .builder()
            .claim("email", email)
            .subject(id.toString())
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact()
    }

    fun generateRefreshToken(id: Long): String {
        val now = Date()
        val validity = Date(now.time + refreshExp)

        return Jwts
            .builder()
            .subject(id.toString())
            .issuedAt(now)
            .expiration(validity)
            .signWith(secretKey)
            .compact()
    }

    /**
     * 토큰의 subject에서 사용자 ID를 꺼낸다.
     *
     * subject가 숫자가 아니면 null을 돌려준다. 기존에는 toLong()을 써서
     * NumberFormatException이 필터 안에서 터졌고, @RestControllerAdvice가 필터 예외를
     * 잡지 못하므로 조작된 토큰 하나로 500을 만들 수 있었다.
     */
    fun getIdFromToken(token: String): Long? =
        Jwts
            .parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
            .subject
            ?.toLongOrNull()

    fun getEmailFromToken(token: String): String? =
        Jwts
            .parser()
            .verifyWith(secretKey)
            .build()
            .parseSignedClaims(token)
            .payload
            .get("email", String::class.java)

    /**
     * 서명과 만료를 검증한다.
     *
     * ExpiredJwtException을 JwtException보다 먼저 잡아야 한다.
     * 전자가 후자의 하위 타입이라 순서를 바꾸면 만료가 INVALID로 뭉개진다.
     */
    fun validateToken(token: String): TokenStatus =
        try {
            Jwts
                .parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
            TokenStatus.VALID
        } catch (e: ExpiredJwtException) {
            TokenStatus.EXPIRED
        } catch (e: JwtException) {
            // 서명 불일치·형식 오류 등 위조로 볼 수 있는 모든 경우
            TokenStatus.INVALID
        } catch (e: IllegalArgumentException) {
            // 토큰이 빈 문자열이거나 null인 경우 jjwt가 던진다
            TokenStatus.INVALID
        }
}
