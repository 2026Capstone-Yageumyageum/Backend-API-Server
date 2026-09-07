package com.capstone.backend.domain.analysis.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class AnalysisResponse(
    val videoId: String,
    val status: String,
    @JsonProperty("user_data")
    val userData: UserDataDto,
    val players: List<PlayerAnalysisDto>,
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
    val proId: String,
    val overallScore: Double,
    val phaseScores: List<PhaseScoreDto>,
    val release: ReleaseDto? = null,
    val feedback: FeedbackDto? = null,
    // 구버전 분석 서버는 이 필드를 보내지 않으므로 nullable 이어야 한다.
    val phaseMetrics: List<PhaseMetricDto>? = null,
)

/**
 * 구간별 상세 지표. 분석 서버가 산출한 값을 그대로 통과시키기 위한 DTO다.
 *
 * 백엔드는 이 값을 해석하지 않는다. 여기에 필드를 선언하는 이유는 오직 하나,
 * 선언하지 않으면 Jackson이 조용히 버려서 앱까지 도달하지 못하기 때문이다.
 */
data class PhaseMetricDto(
    val phase: String,
    val key: String,
    val label: String,
    /** "degree"면 도(°) 단위, null이면 단위 없는 정규화 좌표. 앱이 표기를 나눈다. */
    val unit: String? = null,
    val userValue: Double? = null,
    val proValue: Double? = null,
    val difference: Double? = null,
    val threshold: Double? = null,
    val status: String,
    val favorableDirection: String? = null,
    val why: String? = null,
    /** 이 지표가 측정에 쓴 관절 이름. 손잡이가 서로 다를 수 있어 양쪽을 따로 받는다. */
    val userJoints: List<String>? = null,
    val proJoints: List<String>? = null,
    val userFrame: Double? = null,
    val proFrame: Double? = null,
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
    val results: List<PitchingComparisonDto>,
)

data class PitchingComparisonDto(
    val proId: Long,
    val proName: String,
    val pitchType: String,
    val similarityScore: Double,
    val feedback: String?,
    // 파이썬 player 원본(phaseScores·release·feedback) JSON 문자열. 프론트가 JSON.parse 하여 리포트 상세에 사용.
    val detailJson: String?,
)

data class VideoDetail(
    val videoId: String,
    val fps: Double,
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
    val point: ReleasePointDto,
)

data class ReleaseFrameInfo(
    val frame: Double,
    val beforeFrame: Double,
    val exitFrame: Double,
    val method: String,
    val status: String,
    val source: String,
)

data class ReleaseTimingDto(
    val proPitchPercent: Double,
    val userPitchPercent: Double,
    val differencePercent: Double,
    val message: String,
)

data class ReleasePointDto(
    val difference: Double,
    val heightDifference: Double,
    val sideDifference: Double,
    val message: String,
)

data class FeedbackDto(
    val good: List<FeedbackItemDto>,
    val bad: List<FeedbackItemDto>,
)

data class FeedbackItemDto(
    // 상세 코칭 피드백은 phase가 없을 수 있다(예: 전체 동작 기준 팁)
    val phase: String? = null,
    val message: String,
    val evidence: FeedbackEvidenceDto? = null,
)

data class FeedbackEvidenceDto(
    // 팁 종류에 따라 프레임/퍼센트 정보가 일부만 채워지므로 모두 nullable
    val proFrame: Double? = null,
    val userFrame: Double? = null,
    val proPhasePercent: Double? = null,
    val userPhasePercent: Double? = null,
    val differencePercent: Double? = null,
    val difference: Double? = null,
)
