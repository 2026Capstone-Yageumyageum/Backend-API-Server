package com.capstone.backend.domain.user.dto

data class GoogleLoginRequest(
    val idToken: String,
)

data class SignupRequest(
    val email: String,
    val nickname: String,
)

data class AuthResponse(
    val email: String,
    val isRegistered: Boolean,
    val message: String,
    val accessToken: String? = null
)

data class RefreshRequest(
    val refreshToken: String
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String
)
