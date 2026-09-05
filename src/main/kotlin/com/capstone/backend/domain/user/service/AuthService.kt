package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.user.dto.AuthResponse
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.dto.TokenResponse
import com.capstone.backend.domain.user.entity.RefreshToken
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.RefreshTokenRepository
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import com.capstone.backend.global.util.JwtUtil
import com.capstone.backend.global.util.TokenStatus
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import jakarta.annotation.PostConstruct
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class AuthService(
    private val userRepository: UserRepository,
    @Value("\${spring.security.oauth2.client.registration.google.client-id}")
    private val googleClientId: String,
    private val jwtUtil: JwtUtil,
    private val refreshTokenRepository: RefreshTokenRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()
    private lateinit var verifier: GoogleIdTokenVerifier

    @PostConstruct
    fun init() {
        verifier =
            GoogleIdTokenVerifier
                .Builder(transport, jsonFactory)
                .setAudience(listOf(googleClientId))
                .build()
    }

    fun verifyGoogleToken(idTokenString: String): AuthResponse {
        val idToken =
            verifier.verify(idTokenString)
                ?: throw BusinessException(ErrorCode.INVALID_GOOGLE_TOKEN)
        val email = idToken.payload.email
        return processUserLoginOrSignup(email)
    }

    @Transactional
    fun processUserLoginOrSignup(email: String): AuthResponse {
        val existingUser = userRepository.findByEmail(email)

        return if (existingUser != null) {
            val accessToken = jwtUtil.generateAccessToken(existingUser.id!!, existingUser.email)
            val refreshToken = jwtUtil.generateRefreshToken(existingUser.id!!)

            refreshTokenRepository.save(
                RefreshToken(
                    refreshToken = refreshToken,
                    userId = existingUser.id!!,
                    ttl = jwtUtil.refreshExp / 1000,
                ),
            )
            AuthResponse(email, true, "로그인 성공", accessToken, refreshToken)
        } else {
            AuthResponse(email, false, "회원가입이 필요합니다. 닉네임을 입력해주세요.", null, null)
        }
    }

    @Transactional
    fun signup(request: SignupRequest): AuthResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }
        if (userRepository.existsByNickname(request.nickname)) {
            throw BusinessException(ErrorCode.DUPLICATE_NICKNAME)
        }
        val newUser =
            User(
                email = request.email,
                nickname = request.nickname,
            )
        val savedUser = userRepository.save(newUser)

        val accessToken = jwtUtil.generateAccessToken(savedUser.id!!, savedUser.email)
        val refreshToken = jwtUtil.generateRefreshToken(savedUser.id!!)

        refreshTokenRepository.save(
            RefreshToken(
                refreshToken = refreshToken,
                userId = savedUser.id!!,
                ttl = jwtUtil.refreshExp / 1000,
            ),
        )
        return AuthResponse(request.email, true, "회원가입이 완료되었습니다.", accessToken, refreshToken)
    }

    @Transactional
    fun refreshTokens(requestToken: String): TokenResponse {
        if (jwtUtil.validateToken(requestToken) != TokenStatus.VALID) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }
        val storedToken =
            refreshTokenRepository
                .findById(requestToken)
                .orElseThrow { BusinessException(ErrorCode.INVALID_REFRESH_TOKEN) }
        refreshTokenRepository.delete(storedToken)
        val userID = storedToken.userId
        val user =
            userRepository
                .findById(userID)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val newAccessToken = jwtUtil.generateAccessToken(userID, user.email)
        val newRefreshToken = jwtUtil.generateRefreshToken(userID)

        refreshTokenRepository.save(
            RefreshToken(
                refreshToken = newRefreshToken,
                userId = userID,
                ttl = jwtUtil.refreshExp / 1000,
            ),
        )
        return TokenResponse(newAccessToken, newRefreshToken)
    }

    /**
     * 로그아웃: 서버에 저장된 리프레시 토큰을 폐기한다.
     *
     * 왜 서버에서도 지워야 하는가:
     * 클라이언트가 자기 저장소에서 토큰을 지우는 것만으로는 로그아웃이 끝나지 않는다.
     * 서버는 그 토큰을 여전히 유효하다고 보므로, 값을 가진 누구든 /refresh 를 계속 호출할 수 있다.
     * 게다가 갱신할 때마다 TTL이 새로 연장되니, 한 번 유출되면 사실상 기한이 없어진다.
     * 사용자의 "그만 쓰겠다"는 의사를 서버에 반영하는 지점이 여기다.
     *
     * 설계 원칙 — 이 메서드는 실패하지 않는다:
     *  - 이미 지워진 토큰이어도 조용히 통과한다(멱등). 404를 주면 "그 토큰이 있었는지"를
     *    알려주는 셈이고, 클라이언트 입장에서도 재시도할 것이 없다.
     *  - 만료된 토큰은 지울 것이 없다. Redis TTL이 JWT 만료와 같은 시각에 끝나기 때문이다.
     *  - 위조된 토큰은 무시한다. 아무 문자열이나 저장소 삭제를 시도하게 두지 않는다.
     *
     * @Transactional을 붙이지 않는 이유: 대상이 Redis 저장소 하나뿐이라
     * JPA 트랜잭션이 관여할 것이 없다. 붙이면 보호하는 것처럼 보이지만 실제로는 아무 효과가 없다.
     */
    fun logout(refreshToken: String) {
        val status = jwtUtil.validateToken(refreshToken)
        if (status != TokenStatus.VALID) {
            // 사용자에게는 성공으로 응답한다. 남길 가치가 있는 것은 원인뿐이다.
            log.warn("로그아웃 요청의 리프레시 토큰이 유효하지 않습니다. status={}", status)
            return
        }
        refreshTokenRepository.deleteById(refreshToken)
    }
}
