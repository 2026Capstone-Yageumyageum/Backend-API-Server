package com.capstone.backend.domain.user.controller

import com.capstone.backend.domain.user.dto.GrowthPointResponse
import com.capstone.backend.domain.user.dto.MyAnalysisItemResponse
import com.capstone.backend.domain.user.dto.ProSummaryResponse
import com.capstone.backend.domain.user.dto.UserProfileResponse
import com.capstone.backend.domain.user.dto.UserStatsResponse
import com.capstone.backend.domain.user.service.UserService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
) {
    @GetMapping("/me")
    fun getMe(
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<UserProfileResponse> {
        val id = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")
        return ResponseEntity.ok(userService.getProfile(id))
    }

    @GetMapping("/me/stats")
    fun getMyStats(
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<UserStatsResponse> {
        val id = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")
        return ResponseEntity.ok(userService.getStats(id))
    }

    @GetMapping("/me/analyses")
    fun getMyAnalyses(
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<List<MyAnalysisItemResponse>> {
        val id = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")
        return ResponseEntity.ok(userService.getMyAnalyses(id))
    }

    // 내가 비교당한 프로 목록 (마이페이지 그래프 드롭다운)
    @GetMapping("/me/pros")
    fun getComparedPros(
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<List<ProSummaryResponse>> {
        val id = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")
        return ResponseEntity.ok(userService.getComparedPros(id))
    }

    // 특정 프로에 대한 내 점수 변화 추이 (마이페이지 프로별 그래프)
    @GetMapping("/me/growth")
    fun getProGrowth(
        @AuthenticationPrincipal userId: Long?,
        @org.springframework.web.bind.annotation.RequestParam proId: Long,
    ): ResponseEntity<List<GrowthPointResponse>> {
        val id = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")
        return ResponseEntity.ok(userService.getProGrowth(id, proId))
    }
}
