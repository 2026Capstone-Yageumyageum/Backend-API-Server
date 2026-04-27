package com.capstone.backend.domain.user.controller

import com.capstone.backend.domain.user.dto.AuthResponse
import com.capstone.backend.domain.user.dto.GoogleLoginRequest
import com.capstone.backend.domain.user.dto.SignupRequest
import com.capstone.backend.domain.user.dto.TokenResponse
import com.capstone.backend.domain.user.service.AuthService
import com.google.api.client.auth.oauth2.RefreshTokenRequest
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
        @RequestBody request: GoogleLoginRequest,
    ): ResponseEntity<AuthResponse> {
        val response = authService.verifyGoogleToken(request.idToken)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/signup")
    fun signup(
        @RequestBody request: SignupRequest,
    ): ResponseEntity<AuthResponse> {
        val response = authService.signup(request)
        return ResponseEntity.ok(response)
    }
    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshTokenRequest): ResponseEntity<TokenResponse> {
        val response = authService.refreshTokens(request.refreshToken)
        return ResponseEntity.ok(response)
    }
}
