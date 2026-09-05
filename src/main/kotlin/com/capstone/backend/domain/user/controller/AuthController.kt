package com.capstone.backend.domain.user.controller

import com.capstone.backend.domain.user.dto.AuthResponse
import com.capstone.backend.domain.user.dto.GoogleLoginRequest
import com.capstone.backend.domain.user.dto.LogoutResponse
import com.capstone.backend.domain.user.dto.RefreshRequest
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.dto.TokenResponse
import com.capstone.backend.domain.user.service.AuthService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authService: AuthService,
) {
    @PostMapping("/google")
    fun googleLogin(
        @Valid @RequestBody request: GoogleLoginRequest,
    ): ResponseEntity<AuthResponse> {
        val response = authService.verifyGoogleToken(request.idToken)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/signup")
    fun signup(
        @Valid @RequestBody request: SignupRequest,
    ): ResponseEntity<AuthResponse> {
        val response = authService.signup(request)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/refresh")
    fun refresh(
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<TokenResponse> {
        val response = authService.refreshTokens(request.refreshToken)
        return ResponseEntity.ok(response)
    }

    /**
     * 로그아웃: 서버에 저장된 리프레시 토큰을 폐기한다.
     *
     * 인증(액세스 토큰)을 요구하지 않는다:
     * 로그아웃하는 시점에는 액세스 토큰이 이미 만료됐을 가능성이 높다. 인증을 요구하면
     * "만료돼서 로그아웃도 못 하는" 상황이 생긴다. 리프레시 토큰 값을 제시하는 것 자체가
     * 소유 증명 역할을 하고, 그 토큰을 폐기하는 일은 소유자에게 해가 되지 않는다.
     *
     * 항상 200으로 응답한다. 토큰이 이미 없거나 위조됐어도 마찬가지다(AuthService.logout 참고).
     */
    @PostMapping("/logout")
    fun logout(
        @Valid @RequestBody request: RefreshRequest,
    ): ResponseEntity<LogoutResponse> {
        authService.logout(request.refreshToken)
        return ResponseEntity.ok(LogoutResponse("로그아웃되었습니다."))
    }
}
