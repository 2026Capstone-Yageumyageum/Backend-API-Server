package com.capstone.backend.domain.analysis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class AnalysisResponse(
    val videoId: String,
    val status: String,
    @JsonProperty("user_data")
    val userData: UserDataDto,
    val players: List<PlayerAnalysisDto>
)

data class UserDataDto(
    @JsonProperty("skeleton_data_id")
    val skeletonDataId: String,
    @JsonProperty("skeleton_data")
    val skeletonDataCsv: String,
    @JsonProperty("frame_count")
    val frameCount: Int,
    val fps: Double,
    val resolution: String,
)

data class ReferenceDataResponse(
    val proId: Long,
    val pitcherName: String,
    val pitchType: String,
    @JsonProperty("skeleton_data")
    val skeletonData: String,
)

data class PlayerAnalysisDto(
    val analysisId: String,
    val proId: Long,
    val overallScore: Double,
    val phaseScores: List<PhaseScoreDto>,
    val release: ReleaseDto? = null,
    val feedback: FeedbackDto? = null,
)

data class PhaseScoreDto(
    val phase: String,
    val label: String,
    val score: Double,
    val userStartFrame: Double,
    val userEndFrame: Double,
    val proStartFrame: Double,
    val proEndFrame: Double,
)

data class AnalysisResultResponse(
    val videoId: Long,
    val status: String,
    val results: List<PitchingComparisonDto>
)

data class PitchingComparisonDto(
    val proName: String,
    val pitchType: String,
    val similarityScore: Double,
    val feedback: String?
)

data class VideoDetail(
    val videoId: String,
    val fps: Int,
    val durationSec: Double,
    val width: Int,
    val height: Int,
    val playerName: String? = null,
)

data class ReleaseDto(
    val proFrame: Double,
    val userFrame: Double,
    val pro: ReleaseFrameInfo?,
    val user: ReleaseFrameInfo?,
    val timing: ReleaseTimingDto,
    val point: ReleasePointDto
)

data class ReleaseFrameInfo(
    val frame: Double,
    val beforeFrame: Double,
    val exitFrame: Double,
    val method: String,
    val status: String,
    val source: String
)

data class ReleaseTimingDto(
    val proPitchPercent: Double,
    val userPitchPercent: Double,
    val differencePercent: Double,
    val message: String
)

data class ReleasePointDto(
    val difference: Double,
    val heightDifference: Double,
    val sideDifference: Double,
    val message: String
)

data class FeedbackDto(
    val good: List<FeedbackItemDto>,
    val bad: List<FeedbackItemDto>
)

data class FeedbackItemDto(
    val phase: String,
    val message: String,
    val evidence: FeedbackEvidenceDto?
)

data class FeedbackEvidenceDto(
    val proFrame: Double,
    val userFrame: Double,
    val proPhasePercent: Double,
    val userPhasePercent: Double,
    val differencePercent: Double,
    val difference: Double
)
