package com.capstone.backend.domain.user.dto

// 마이 화면 상단 프로필
data class UserProfileResponse(
    val nickname: String,
    val email: String,
    val recentAnalysisCount: Int, // 최근 30일 완료 분석 수
)

// 마이 화면 통계(대시보드)
data class UserStatsResponse(
    val nickname: String,
    val totalSessions: Int,
    val bestScore: Int,
    val thisMonthSessions: Int,
    val recentAnalysisCount: Int,
    val growth: List<GrowthPointResponse>,
    val pitchDistribution: List<PitchDistributionResponse>,
)

data class GrowthPointResponse(
    val label: String, // 예: "4/28"
    val value: Int, // 해당 분석의 Top 유사도
)

data class PitchDistributionResponse(
    val type: String, // 구종명
    val count: Int,
    val percentage: Int, // 0~100
)

// 피드(프로 탭) 한 줄
data class MyAnalysisItemResponse(
    val videoId: Long,
    val date: String, // 예: "2025.04.28"
    val playerName: String, // Top 프로 선수
    val pitchType: String,
    val similarity: Int, // 0~100
)

// 마이페이지 프로별 그래프 드롭다운용 (가벼운 프로 목록)
data class ProSummaryResponse(
    val proId: Long,
    val pitcherName: String,
)
