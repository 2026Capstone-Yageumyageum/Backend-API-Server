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
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.persistence.EntityNotFoundException
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
                .orElseThrow { EntityNotFoundException("사용자를 찾을 수 없습니다.") }
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
                .orElseThrow { EntityNotFoundException("해당 영상 정보를 찾을 수 없습니다") }
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
                    throw RuntimeException("AI 서버 분석 실패: 상태 이상")
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
                            ?: throw IllegalArgumentException("프로 선수 ID가 숫자가 아닙니다: ${playerDto.proId}")
                    }
                val referenceModels = referenceModelRepository.findAllById(top3ProIds)

                val analysisResult =
                    response.players.map { playerDto ->
                        val proId =
                            playerDto.proId.toLongOrNull()
                                ?: throw IllegalArgumentException("프로 선수 ID가 숫자가 아닙니다: ${playerDto.proId}")
                        val matchedProModel =
                            referenceModels.find { it.id == proId }
                                ?: throw IllegalArgumentException("DB에 존재하지 않는 프로 선수 ID 반환됨: ${playerDto.proId}")
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
                .orElseThrow { EntityNotFoundException("해당 영상 정보를 찾을 수 없습니다") }
        require(video.user.id == userId) { "본인 영상만 최고의 1구로 등록할 수 있습니다." }
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
                .orElseThrow { EntityNotFoundException("해당 영상 정보를 찾을 수 없습니다") }
        val bestPitch =
            userVideoRepository
                .findById(bestPitchVideoId)
                .orElseThrow { EntityNotFoundException("비교할 최고의 1구 영상을 찾을 수 없습니다") }
        val bestCsv =
            bestPitch.skeletonData?.skeletonData?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("최고의 1구 영상의 골격 데이터가 없습니다. 먼저 분석을 완료해 주세요.")

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
                    throw RuntimeException("AI 서버 분석 실패: 상태 이상")
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
    fun getAnalysisResult(videoId: Long): AnalysisResultResponse {
        val userVideo =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { NoSuchElementException("영상을 찾을 수 없습니다.") }

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
    fun getSkeletonData(videoId: Long): Map<String, Any> {
        val userVideo =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { NoSuchElementException("영상을 찾을 수 없습니다.") }
        return mapOf(
            "skeletonData" to (userVideo.skeletonData?.skeletonData ?: ""),
            "frameCount" to (userVideo.skeletonData?.frameCount ?: 0),
        )
    }
}
