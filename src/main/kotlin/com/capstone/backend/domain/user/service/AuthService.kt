package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.user.dto.AuthResponse
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.dto.TokenResponse
import com.capstone.backend.domain.user.entity.RefreshToken
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.RefreshTokenRepository
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.global.util.JwtUtil
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import jakarta.annotation.PostConstruct
import jakarta.transaction.Transactional
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
                ?: throw IllegalStateException("유효하지 않은 구글 토큰입니다")
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
            throw IllegalArgumentException("이미 가입된 메일입니다.")
        }
        if (userRepository.existsByNickname(request.nickname)) {
            throw IllegalArgumentException("이미 사용 중인 닉네임입니다.")
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
        if (!jwtUtil.validateToken(requestToken)) {
            throw IllegalArgumentException("유효하지 않거나 만료된 리프레시 토큰입니다.")
        }
        val storedToken =
            refreshTokenRepository
                .findById(requestToken)
                .orElseThrow {
                    IllegalArgumentException("이미 사용되었거나 만료된 토큰입니다. 다시 로그인해주세요.")
                }
        refreshTokenRepository.delete(storedToken)
        val userID = storedToken.userId
        val user =
            userRepository
                .findById(userID)
                .orElseThrow {
                    IllegalArgumentException("사용자를 찾을 수 없습니다.")
                }
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
}
