package com.capstone.backend_server.domain.user.controller

import com.capstone.backend_server.domain.user.dto.AuthResponse
import com.capstone.backend_server.domain.user.dto.GoogleLoginRequest
import com.capstone.backend_server.domain.user.dto.SignupRequest
import com.capstone.backend_server.domain.user.service.AuthService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/auth")
class AuthController (
    private val authService: AuthService,
){
    @PostMapping("/google")
    fun googleLogin(@RequestBody request: GoogleLoginRequest): ResponseEntity<AuthResponse>{
        val response = authService.verifyGoogleToken(request.idToken)
        return ResponseEntity.ok(response)
    }

    @PostMapping("/signup")
    fun signup(@RequestBody request: SignupRequest): ResponseEntity<AuthResponse>{
        val response = authService.signup(request)
        return ResponseEntity.ok(response)
    }
}