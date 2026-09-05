package com.capstone.backend.domain.user.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

// Kotlin의 data class 생성자 파라미터에 붙는 어노테이션은 파라미터·필드·게터 중
// 어디에 적용될지 모호하다. Bean Validation은 필드를 보므로 @field: 로 명시한다.
// 길이 제한은 User 엔티티의 컬럼 정의와 맞췄다. DB 제약 위반(500)보다 먼저 400으로 거른다.
data class GoogleLoginRequest(
    @field:NotBlank(message = "구글 ID 토큰은 필수입니다.")
    val idToken: String,
)

data class SignupRequest(
    @field:NotBlank(message = "이메일은 필수입니다.")
    @field:Email(message = "올바른 이메일 형식이 아닙니다.")
    @field:Size(max = 255, message = "이메일은 255자를 넘을 수 없습니다.")
    val email: String,
    @field:NotBlank(message = "닉네임은 필수입니다.")
    @field:Size(min = 2, max = 50, message = "닉네임은 2자 이상 50자 이하여야 합니다.")
    val nickname: String,
)

data class AuthResponse(
    val email: String,
    val isRegistered: Boolean,
    val message: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
)

data class RefreshRequest(
    @field:NotBlank(message = "리프레시 토큰은 필수입니다.")
    val refreshToken: String,
)

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
)
