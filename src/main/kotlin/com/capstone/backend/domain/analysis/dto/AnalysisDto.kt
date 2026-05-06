package com.capstone.backend.domain.analysis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class AnalysisResponse(
    val videoId: String,
    val status: String,
    @JsonProperty("user_data")
    val userData: UserDataDto
)



data class UserDataDto(
    @JsonProperty("skeleton_data_id")
    val skeletonDataId: String,
    @JsonProperty("skeleton_data")
    val skeletonDataCsv: String,
    @JsonProperty("frame_count")
    val frameCount: Int,
    val fps: Int,
    val resolution: String,
    val players: List<PlayerAnalysisDto>
)

data class ReferenceDataResponse(
    val proId: Long,
    val pitcherName: String,
    val pitchType: String,
    @JsonProperty("skeleton_data")
    val skeletonData: String
)

data class PlayerAnalysisDto(
    val analysisId: String,
    val proId: Long, // ReferenceModel의 ID와 매핑됩니다[cite: 2, 4]
    val overallScore: Double,
    val phaseScores: List<PhaseScoreDto>
)

data class PhaseScoreDto(
    val phase: String,
    val label: String,
    val score: Int,
    val userStartFrame: Int,
    val userEndFrame: Int,
    val proStartFrame: Int,
    val proEndFrame: Int
)

data class VideoDetail(
    val videoId: String,
    val fps: Int,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val playerName: String? = null // 프로 투수 데이터일 경우에만 존재
)
