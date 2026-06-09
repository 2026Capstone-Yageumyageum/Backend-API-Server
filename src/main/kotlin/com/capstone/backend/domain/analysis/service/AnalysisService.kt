package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.PitchingComparisonDto
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import jakarta.persistence.EntityNotFoundException
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.domain.video.repository.UserVideoRepository
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
) {
    fun requestPitchingAnalysisAsync(
        videoId: Long,
        videoResource: Resource,
    ): Mono<List<AnalysisResult>> {
        val userVideo = userVideoRepository.findById(videoId)
            .orElseThrow { EntityNotFoundException("해당 영상 정보를 찾을 수 없습니다") }
        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("userVideo", videoResource)
        val metadataJson = """
            {
                "videoId": "$videoId",
                "analysisType": "pro_similarity",
                "pitchType": "직구",
                "cameraView": "rear"
            }
        """.trimIndent()
        bodyBuilder.part("metadata", metadataJson, org.springframework.http.MediaType.APPLICATION_JSON)

        return pythonWebClient
            .post()
            .uri("/api/analyze/similarity")
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
                userVideo.status = "COMPLETED"
                userVideoRepository.save(userVideo)

                val top3ProIds = response.players.map { it.proId }
                val referenceModels = referenceModelRepository.findAllById(top3ProIds)

                val analysisResult =
                    response.players.map { playerDto ->
                        val matchedProModel =
                            referenceModels.find { it.id == playerDto.proId }
                                ?: throw IllegalArgumentException("DB에 존재하지 않는 프로 선수 ID 반환됨: ${playerDto.proId}")
                        AnalysisResult(
                            similarityScore = playerDto.overallScore,
                            feedbackText = "분석 완료 (구간 수: ${playerDto.phaseScores.size})",
                            userVideo = userVideo,
                            referenceModel = matchedProModel,
                        )
                    }
                analysisResultRepository.saveAll(analysisResult)
            }.doOnError { error ->
                println("비동기 AI 분석 중 치명적 에러: ${error.message}")
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
        val userVideo = userVideoRepository.findById(videoId)
            .orElseThrow { NoSuchElementException("영상을 찾을 수 없습니다.") }

        val results = analysisResultRepository.findByUserVideoId(videoId).map{
            PitchingComparisonDto(
                proName = it.referenceModel.pitcherName,
                pitchType = it.referenceModel.pitchType,
                similarityScore = it.similarityScore,
                feedback = it.feedbackText
            )
        }
        return AnalysisResultResponse(
                videoId = videoId,
                status = userVideo.status,
                results = results
        )
    }
    @Transactional(readOnly = true)
    fun getSkeletonData(videoId: Long): Map<String, Any> {
        val userVideo = userVideoRepository.findById(videoId)
            .orElseThrow { NoSuchElementException("영상을 찾을 수 없습니다.") }
        return mapOf(
            "skeletonData" to (userVideo.skeletonData?.skeletonData ?: ""),
            "frameCount" to (userVideo.skeletonData?.frameCount ?: 0)
        )
    }
}
