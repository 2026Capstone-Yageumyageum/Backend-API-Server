package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.PitchingComparisonDto
import com.capstone.backend.domain.analysis.dto.PlayerAnalysisDto
import com.capstone.backend.domain.analysis.dto.SkeletonDataResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.UserVideoRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import tools.jackson.databind.ObjectMapper

/** 사용자가 구종을 고르지 않았을 때 쓰는 기본값. */
const val DEFAULT_PITCH_TYPE = "직구"

/** 비교 종류. AnalysisResult.comparisonType 컬럼 값과 일치해야 한다. */
const val COMPARISON_PRO = "PRO"
const val COMPARISON_BEST_PITCH = "BEST_PITCH"

/** 파이썬 분석 서버가 성공했을 때 돌려주는 status 값. */
private const val PYTHON_STATUS_COMPLETED = "completed"

/**
 * 전체 프레임 대신 360프레임을 균등 샘플링해 분석 속도를 높인다.
 * 프로 레퍼런스도 같은 방식으로 추출되므로 비교 일관성이 유지된다.
 */
private const val MAX_FRAMES = 360

@Service
class AnalysisService(
    private val pythonWebClient: WebClient,
    private val objectMapper: ObjectMapper,
    private val analysisResultRepository: AnalysisResultRepository,
    private val referenceModelRepository: ReferenceModelRepository,
    private val userVideoRepository: UserVideoRepository,
    private val userRepository: UserRepository,
    private val resultWriter: AnalysisResultWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 원샷 업로드용: 분석 전에 PENDING 상태의 UserVideo를 먼저 만들어 videoId를 확보한다.
    @Transactional
    fun createPendingVideo(
        userId: Long,
        sourceLabel: String?,
        pitchType: String,
    ): UserVideo {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        return userVideoRepository.save(
            UserVideo(
                user = user,
                videoUrl = sourceLabel?.takeIf { it.isNotBlank() } ?: "client_local_video",
                pitchType = pitchType,
            ),
        )
    }

    /** 프로 선수와 비교 분석한다. 파이썬 서버가 캐시된 프로 레퍼런스 중 상위 후보를 골라 돌려준다. */
    fun requestPitchingAnalysisAsync(
        videoId: Long,
        videoResource: Resource,
        trimStartSec: Double? = null,
        trimEndSec: Double? = null,
    ): Mono<List<AnalysisResult>> {
        val userVideo = findVideo(videoId)
        val metadata =
            baseMetadata(videoId, userVideo, "pro_similarity", trimStartSec, trimEndSec).apply {
                this["user"] = mapOf("videoId" to videoId.toString())
            }
        return executeAnalysis(videoId, videoResource, metadata, ::buildProResults)
    }

    /** 내 영상을 내 "최고의 1구"와 비교한다. 프로 캐시 대신 best 영상의 골격을 레퍼런스로 보낸다. */
    fun requestBestPitchAnalysisAsync(
        videoId: Long,
        videoResource: Resource,
        bestPitchVideoId: Long,
        trimStartSec: Double? = null,
        trimEndSec: Double? = null,
    ): Mono<List<AnalysisResult>> {
        val userVideo = findVideo(videoId)
        val bestPitch =
            userVideoRepository
                .findById(bestPitchVideoId)
                .orElseThrow { BusinessException(ErrorCode.BEST_PITCH_VIDEO_NOT_FOUND) }
        val bestCsv =
            bestPitch.skeletonData?.skeletonData?.takeIf { it.isNotBlank() }
                ?: throw BusinessException(ErrorCode.SKELETON_DATA_NOT_READY)

        val metadata =
            baseMetadata(videoId, userVideo, "best_pitch_similarity", trimStartSec, trimEndSec).apply {
                this["referenceSkeletons"] =
                    listOf(mapOf("proId" to bestPitchVideoId.toString(), "skeleton_data" to bestCsv))
            }
        return executeAnalysis(videoId, videoResource, metadata) { video, response ->
            buildBestPitchResults(video, response, bestPitchVideoId)
        }
    }

    /**
     * 두 분석 방식의 공통 골격.
     *
     * 이전에는 이 흐름 전체가 두 메서드에 그대로 복사되어 있었다. 차이는 metadata 구성과
     * 결과 매핑뿐이므로 그 둘만 파라미터로 받는다.
     */
    private fun executeAnalysis(
        videoId: Long,
        videoResource: Resource,
        metadata: Map<String, Any?>,
        buildResults: (UserVideo, AnalysisResponse) -> List<AnalysisResult>,
    ): Mono<List<AnalysisResult>> {
        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("userVideo", videoResource)
        // CSV에 콤마·줄바꿈이 들어 있어 문자열 결합은 위험하다. 반드시 직렬화해서 보낸다.
        bodyBuilder.part("metadata", objectMapper.writeValueAsString(metadata), MediaType.APPLICATION_JSON)

        return pythonWebClient
            .post()
            .uri("/api/analyze")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono(AnalysisResponse::class.java)
            // JPA는 블로킹 I/O다. 이벤트 루프 스레드를 막지 않도록 전용 풀로 옮긴다.
            .publishOn(Schedulers.boundedElastic())
            .map { response ->
                if (response.status != PYTHON_STATUS_COMPLETED) {
                    throw BusinessException(
                        ErrorCode.ANALYSIS_FAILED,
                        "분석 서버가 완료 상태를 반환하지 않았습니다: ${response.status}",
                    )
                }
                resultWriter.saveSuccess(videoId, response, buildResults)
            }.onErrorResume { error ->
                // doOnError를 쓰지 않는 이유: WebClient 통신 단계에서 실패하면 그 콜백이
                // Netty 이벤트 루프에서 실행되어, 블로킹 DB 저장이 이벤트 루프를 막는다.
                // 여기서는 boundedElastic으로 명시적으로 옮긴 뒤 상태를 기록하고,
                // 원래 예외는 그대로 흘려보내 호출부가 처리하게 한다.
                log.error("분석 실패로 영상을 FAILED 처리합니다. videoId={}", videoId, error)
                Mono
                    .fromCallable { resultWriter.markFailed(videoId) }
                    .subscribeOn(Schedulers.boundedElastic())
                    .then(Mono.error(error))
            }
    }

    /** 파이썬에 보낼 metadata의 공통 부분. */
    private fun baseMetadata(
        videoId: Long,
        userVideo: UserVideo,
        analysisType: String,
        trimStartSec: Double?,
        trimEndSec: Double?,
    ): LinkedHashMap<String, Any?> {
        val metadata =
            linkedMapOf<String, Any?>(
                "videoId" to videoId.toString(),
                "analysisType" to analysisType,
                "cameraView" to "rear",
                "pitchType" to (userVideo.pitchType ?: DEFAULT_PITCH_TYPE),
                "maxFrames" to MAX_FRAMES,
            )
        // 앱 트리머로 고른 구간이 있으면 그 구간만 분석하도록 전달한다.
        if (trimStartSec != null) metadata["userTrimStartSec"] = trimStartSec
        if (trimEndSec != null) metadata["userTrimEndSec"] = trimEndSec
        return metadata
    }

    /** 프로 비교 결과 매핑. resultWriter의 트랜잭션 안에서 실행된다. */
    private fun buildProResults(
        userVideo: UserVideo,
        response: AnalysisResponse,
    ): List<AnalysisResult> {
        val proIds = response.players.map { parseProId(it) }
        val referenceModels = referenceModelRepository.findAllById(proIds).associateBy { it.id }

        return response.players.map { playerDto ->
            val proId = parseProId(playerDto)
            val matched =
                referenceModels[proId]
                    ?: throw BusinessException(
                        ErrorCode.REFERENCE_MODEL_NOT_FOUND,
                        "분석 서버가 DB에 없는 프로 선수 ID를 반환했습니다: ${playerDto.proId}",
                    )
            AnalysisResult(
                similarityScore = playerDto.overallScore,
                feedbackText = "분석 완료 (구간 수: ${playerDto.phaseScores.size})",
                detailJson = objectMapper.writeValueAsString(playerDto),
                comparisonType = COMPARISON_PRO,
                userVideo = userVideo,
                referenceModel = matched,
            )
        }
    }

    /** 최고의 1구 비교 결과 매핑. 비교 대상은 트랜잭션 안에서 다시 조회한다. */
    private fun buildBestPitchResults(
        userVideo: UserVideo,
        response: AnalysisResponse,
        bestPitchVideoId: Long,
    ): List<AnalysisResult> {
        val bestPitch =
            userVideoRepository
                .findById(bestPitchVideoId)
                .orElseThrow { BusinessException(ErrorCode.BEST_PITCH_VIDEO_NOT_FOUND) }

        return response.players.map { playerDto ->
            AnalysisResult(
                similarityScore = playerDto.overallScore,
                feedbackText = "최고의 1구 비교 완료 (구간 수: ${playerDto.phaseScores.size})",
                detailJson = objectMapper.writeValueAsString(playerDto),
                comparisonType = COMPARISON_BEST_PITCH,
                userVideo = userVideo,
                referenceModel = null,
                bestPitchVideo = bestPitch,
            )
        }
    }

    private fun parseProId(playerDto: PlayerAnalysisDto): Long =
        playerDto.proId.toLongOrNull()
            ?: throw BusinessException(
                ErrorCode.INVALID_ANALYSIS_RESPONSE,
                "분석 서버가 숫자가 아닌 프로 선수 ID를 반환했습니다: ${playerDto.proId}",
            )

    // 영상을 (user, pitchType)의 "최고의 1구"로 등록한다. 같은 구종의 기존 best는 해제한다.
    @Transactional
    fun registerBestPitch(
        userId: Long,
        videoId: Long,
    ) {
        val video = findOwnedVideo(userId, videoId)
        val pitchType = video.pitchType ?: DEFAULT_PITCH_TYPE
        // 같은 구종의 기존 best 해제 (구종당 1개 유지)
        userVideoRepository
            .findFirstByUserAndPitchTypeAndIsBestPitchTrue(video.user, pitchType)
            ?.takeIf { it.id != video.id }
            ?.let {
                it.isBestPitch = false
                userVideoRepository.save(it)
            }
        video.isBestPitch = true
        userVideoRepository.save(video)
    }

    @Transactional(readOnly = true)
    fun getAnalysisResult(
        userId: Long,
        videoId: Long,
    ): AnalysisResultResponse {
        val userVideo = findOwnedVideo(userId, videoId)

        val results =
            analysisResultRepository.findByUserVideoId(videoId).map {
                val ref = it.referenceModel
                // BEST_PITCH 비교는 referenceModel이 null이므로 비교 대상 "최고의 1구" 정보로 채운다.
                PitchingComparisonDto(
                    proId = ref?.id ?: it.bestPitchVideo?.id ?: 0L,
                    proName = ref?.pitcherName ?: "내 최고의 1구",
                    pitchType = ref?.pitchType ?: (userVideo.pitchType ?: DEFAULT_PITCH_TYPE),
                    similarityScore = it.similarityScore,
                    feedback = it.feedbackText,
                    detailJson = it.detailJson,
                )
            }
        return AnalysisResultResponse(
            videoId = videoId,
            status = userVideo.status,
            results = results,
        )
    }

    @Transactional(readOnly = true)
    fun getSkeletonData(
        userId: Long,
        videoId: Long,
    ): SkeletonDataResponse {
        val userVideo = findOwnedVideo(userId, videoId)
        return SkeletonDataResponse(
            skeletonData = userVideo.skeletonData?.skeletonData ?: "",
            frameCount = userVideo.skeletonData?.frameCount ?: 0,
        )
    }

    private fun findVideo(videoId: Long): UserVideo =
        userVideoRepository
            .findById(videoId)
            .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }

    /**
     * 영상을 조회하되 요청자의 소유인지 함께 확인한다.
     *
     * 조회 API가 videoId만 받고 소유자를 확인하지 않아, 값을 1씩 올려가며
     * 다른 사용자의 분석 결과와 골격 데이터를 볼 수 있었다(IDOR).
     */
    private fun findOwnedVideo(
        userId: Long,
        videoId: Long,
    ): UserVideo {
        val video = findVideo(videoId)
        if (video.user.id != userId) {
            throw BusinessException(ErrorCode.NOT_VIDEO_OWNER)
        }
        return video
    }
}
