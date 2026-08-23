package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.PitchingComparisonDto
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.domain.video.repository.UserVideoRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

@Service
class AnalysisService(
    private val pythonWebClient: WebClient,
    private val analysisResultRepository: AnalysisResultRepository,
    private val referenceModelRepository: ReferenceModelRepository,
    private val skeletonDataRepository: SkeletonDataRepository,
    private val userVideoRepository: UserVideoRepository,
    private val userRepository: UserRepository,
) {
    private val objectMapper = ObjectMapper()

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

    fun requestPitchingAnalysisAsync(
        videoId: Long,
        videoResource: Resource,
        trimStartSec: Double? = null,
        trimEndSec: Double? = null,
    ): Mono<List<AnalysisResult>> {
        val userVideo =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }
        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("userVideo", videoResource)
        // 앱 트리머로 선택한 구간(초)이 있으면 Python이 그 구간만 분석하도록 전달한다.
        val trimJson =
            buildString {
                if (trimStartSec != null) append(",\"userTrimStartSec\":$trimStartSec")
                if (trimEndSec != null) append(",\"userTrimEndSec\":$trimEndSec")
            }
        // maxFrames=360: 전체 프레임 대신 360프레임 균등 샘플링으로 추출해 속도 개선
        // (프로 레퍼런스도 360프레임 균등 샘플링으로 추출되어 비교 일관성도 유지)
        val metadataJson =
            """{"videoId":"$videoId","analysisType":"pro_similarity","cameraView":"rear","pitchType":"${userVideo.pitchType}","maxFrames":360$trimJson,"user":{"videoId":"$videoId"}}"""
        bodyBuilder.part("metadata", metadataJson, MediaType.APPLICATION_JSON)

        return pythonWebClient
            .post()
            .uri("/api/analyze")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono(AnalysisResponse::class.java) // 파이썬 응답을 비동기로 받음[cite: 6]
            // 핵심: JPA 등 DB I/O(블로킹 작업)를 안전하게 처리하기 위해 스레드 풀 전환
            .publishOn(Schedulers.boundedElastic())
            .map { response ->
                if (response.status != "completed") {
                    throw BusinessException(ErrorCode.ANALYSIS_FAILED, "분석 서버가 완료 상태를 반환하지 않았습니다: ${response.status}")
                }
                val userData = response.userData
                val userSkeleton =
                    skeletonDataRepository.save(
                        SkeletonData(
                            skeletonData = userData.skeletonDataCsv,
                            frameCount = userData.frameCount,
                            fps = userData.fps,
                            resolution = userData.resolution,
                        ),
                    )

                userVideo.skeletonData = userSkeleton

                val top3ProIds =
                    response.players.map { playerDto ->
                        playerDto.proId.toLongOrNull()
                            ?: throw BusinessException(
                                ErrorCode.INVALID_ANALYSIS_RESPONSE,
                                "분석 서버가 숫자가 아닌 프로 선수 ID를 반환했습니다: ${playerDto.proId}",
                            )
                    }
                val referenceModels = referenceModelRepository.findAllById(top3ProIds)

                val analysisResult =
                    response.players.map { playerDto ->
                        val proId =
                            playerDto.proId.toLongOrNull()
                                ?: throw BusinessException(
                                    ErrorCode.INVALID_ANALYSIS_RESPONSE,
                                    "분석 서버가 숫자가 아닌 프로 선수 ID를 반환했습니다: ${playerDto.proId}",
                                )
                        val matchedProModel =
                            referenceModels.find { it.id == proId }
                                ?: throw BusinessException(
                                    ErrorCode.REFERENCE_MODEL_NOT_FOUND,
                                    "분석 서버가 DB에 없는 프로 선수 ID를 반환했습니다: ${playerDto.proId}",
                                )
                        AnalysisResult(
                            similarityScore = playerDto.overallScore,
                            feedbackText = "분석 완료 (구간 수: ${playerDto.phaseScores.size})",
                            detailJson = objectMapper.writeValueAsString(playerDto),
                            userVideo = userVideo,
                            referenceModel = matchedProModel,
                        )
                    }
                val saved = analysisResultRepository.saveAll(analysisResult)

                // 결과 행을 모두 저장한 '뒤에야' 상태를 COMPLETED로 전환한다.
                // 이렇게 해야 폴링(getAnalysisResult)이 COMPLETED를 보는 순간 결과도 반드시 존재한다.
                // (기존엔 status를 결과 저장 전에 COMPLETED로 먼저 커밋해, 그 사이에 폴링이 들어오면
                //  결과 0건을 받아 프론트가 목업으로 폴백되던 간헐적 레이스가 있었다.)
                userVideo.status = "COMPLETED"
                userVideoRepository.save(userVideo)
                saved
            }.doOnError { error ->
                println("비동기 AI 분석 중 치명적 에러: ${error.message}")
                userVideo.status = "FAILED"
                userVideoRepository.save(userVideo)
            }
    }

    // 영상을 (user, pitchType)의 "최고의 1구"로 등록한다. 같은 구종의 기존 best는 해제한다.
    @Transactional
    fun registerBestPitch(
        userId: Long,
        videoId: Long,
    ) {
        val video =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }
        if (video.user.id != userId) {
            throw BusinessException(ErrorCode.NOT_VIDEO_OWNER)
        }
        val pitchType = video.pitchType ?: "직구"
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

    // 내 영상을 내 "최고의 1구"와 비교 분석한다. 프로 캐시 대신 best 영상의 골격을 레퍼런스로 보낸다.
    fun requestBestPitchAnalysisAsync(
        videoId: Long,
        videoResource: Resource,
        bestPitchVideoId: Long,
        trimStartSec: Double? = null,
        trimEndSec: Double? = null,
    ): Mono<List<AnalysisResult>> {
        val userVideo =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }
        val bestPitch =
            userVideoRepository
                .findById(bestPitchVideoId)
                .orElseThrow { BusinessException(ErrorCode.BEST_PITCH_VIDEO_NOT_FOUND) }
        val bestCsv =
            bestPitch.skeletonData?.skeletonData?.takeIf { it.isNotBlank() }
                ?: throw BusinessException(ErrorCode.SKELETON_DATA_NOT_READY)

        // CSV에 콤마/줄바꿈이 들어 있어 문자열 결합이 위험하므로 metadata는 ObjectMapper로 직렬화한다.
        val metadataMap =
            linkedMapOf<String, Any?>(
                "videoId" to videoId.toString(),
                "analysisType" to "best_pitch_similarity",
                "cameraView" to "rear",
                "pitchType" to (userVideo.pitchType ?: "직구"),
                "maxFrames" to 360,
                "referenceSkeletons" to
                    listOf(
                        mapOf(
                            "proId" to bestPitchVideoId.toString(),
                            "skeleton_data" to bestCsv,
                        ),
                    ),
            )
        if (trimStartSec != null) metadataMap["userTrimStartSec"] = trimStartSec
        if (trimEndSec != null) metadataMap["userTrimEndSec"] = trimEndSec
        val metadataJson = objectMapper.writeValueAsString(metadataMap)

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("userVideo", videoResource)
        bodyBuilder.part("metadata", metadataJson, MediaType.APPLICATION_JSON)

        return pythonWebClient
            .post()
            .uri("/api/analyze")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
            .retrieve()
            .bodyToMono(AnalysisResponse::class.java)
            .publishOn(Schedulers.boundedElastic())
            .map { response ->
                if (response.status != "completed") {
                    throw BusinessException(ErrorCode.ANALYSIS_FAILED, "분석 서버가 완료 상태를 반환하지 않았습니다: ${response.status}")
                }
                val userData = response.userData
                val userSkeleton =
                    skeletonDataRepository.save(
                        SkeletonData(
                            skeletonData = userData.skeletonDataCsv,
                            frameCount = userData.frameCount,
                            fps = userData.fps,
                            resolution = userData.resolution,
                        ),
                    )
                userVideo.skeletonData = userSkeleton

                val analysisResult =
                    response.players.map { playerDto ->
                        AnalysisResult(
                            similarityScore = playerDto.overallScore,
                            feedbackText = "최고의 1구 비교 완료 (구간 수: ${playerDto.phaseScores.size})",
                            detailJson = objectMapper.writeValueAsString(playerDto),
                            comparisonType = "BEST_PITCH",
                            userVideo = userVideo,
                            referenceModel = null,
                            bestPitchVideo = bestPitch,
                        )
                    }
                val saved = analysisResultRepository.saveAll(analysisResult)

                userVideo.status = "COMPLETED"
                userVideoRepository.save(userVideo)
                saved
            }.doOnError { error ->
                println("최고의 1구 비교 분석 중 치명적 에러: ${error.message}")
                userVideo.status = "FAILED"
                userVideoRepository.save(userVideo)
            }
    }

    @Transactional(readOnly = true)
    fun getAllReferenceData(): List<ReferenceDataResponse> {
        val models = referenceModelRepository.findAll()
        return models.map { model ->
            ReferenceDataResponse(
                proId = model.id!!,
                pitcherName = model.pitcherName,
                pitchType = model.pitchType,
                skeletonData = model.skeletonData.skeletonData,
            )
        }
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
                    pitchType = ref?.pitchType ?: (userVideo.pitchType ?: "직구"),
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
    ): Map<String, Any> {
        val userVideo = findOwnedVideo(userId, videoId)
        return mapOf(
            "skeletonData" to (userVideo.skeletonData?.skeletonData ?: ""),
            "frameCount" to (userVideo.skeletonData?.frameCount ?: 0),
        )
    }

    /**
     * 영상을 조회하되 요청자의 소유인지 함께 확인한다.
     *
     * 기존 조회 API는 videoId만 받고 소유자를 확인하지 않아,
     * 값을 1씩 올려가며 다른 사용자의 분석 결과와 골격 데이터를 볼 수 있었다(IDOR).
     *
     * 존재하지 않는 경우와 남의 것인 경우를 구분해 응답하면 videoId의 존재 여부가
     * 드러나지만, 이미 소유자 검사로 내용은 막히고 사용자에게는 원인이 명확해진다.
     */
    private fun findOwnedVideo(
        userId: Long,
        videoId: Long,
    ): UserVideo {
        val video =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }
        if (video.user.id != userId) {
            throw BusinessException(ErrorCode.NOT_VIDEO_OWNER)
        }
        return video
    }
}
