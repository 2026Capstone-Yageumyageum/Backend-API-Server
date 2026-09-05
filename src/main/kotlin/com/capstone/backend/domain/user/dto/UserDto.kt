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

// 일관성 탭 구종별 "최고의 1구" 카드 한 장
data class BestPitchCardResponse(
    val videoId: Long, // 최고의 1구 영상 id (비교 대상)
    val pitchType: String, // 구종
    val date: String, // 등록(업로드) 날짜 "2025.04.28"
    val bestConsistency: Int, // 이 best와 비교한 기록 중 최고 일관성(없으면 0)
    val sessionCount: Int, // 이 best와 비교한 횟수
    val avgConsistency: Int, // 평균 일관성(없으면 0)
)

// 카드 펼침 목록: 최고의 1구와 비교된 내 기록 한 줄
data class BestPitchComparisonItemResponse(
    val videoId: Long, // 비교한 내 영상 id (리포트 진입용)
    val bestPitchVideoId: Long, // 비교 대상 최고의 1구 id
    val date: String, // 비교 영상 날짜
    val pitchType: String, // 구종
    val consistency: Int, // 일관성 점수 0~100
)
