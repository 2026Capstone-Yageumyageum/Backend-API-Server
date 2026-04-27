package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.user.dto.AuthResponse
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.global.util.JwtUtil
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import jakarta.transaction.Transactional
import org.springframework.beans.factory.annotation.Value

class AuthService(
    private val userRepository: UserRepository,
    @Value("\${spring.security.oauth2.client.registration.google.client-id}")
    private val googleClientId: String,
    private val jwtUtil: JwtUtil,
) {
    @Transactional
    fun verifyGoogleToken(idTokenString: String): AuthResponse {
        val verifier =
            GoogleIdTokenVerifier
                .Builder(NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(listOf(googleClientId))
                .build()
        val idToken =
            verifier.verify(idTokenString)
                ?: throw IllegalStateException("유효하지 않은 구글 토큰입니다.")
        val email = idToken.payload.email
        val existingUser = userRepository.findByEmail(email)

        return if (existingUser != null) {
            val token = jwtUtil.generateAccessToken(existingUser.id!!, existingUser.email)
            AuthResponse(email, true, "로그인 성공",token)
        } else {
            AuthResponse(email, false, "회원가입이 필요합니다. 닉네임을 입력해주세요.",null)
        }
    }

    @Transactional
    fun signup(request: SignupRequest): AuthResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw IllegalArgumentException("이미 가입된 메일입니다.")
        }
        if (userRepository.existsByEmail(request.nickname)) {
            throw IllegalArgumentException("이미 사용 중인 닉네임입니다.")
        }
        val newUser =
            User(
                email = request.email,
                nickname = request.nickname,
            )
        val savedUser = userRepository.save(newUser)

        val token = jwtUtil.generateAccessToken(savedUser.id!!, savedUser.email)
        return AuthResponse(request.email, true, "회원가입이 완료되었습니다.",token)
    }
}
