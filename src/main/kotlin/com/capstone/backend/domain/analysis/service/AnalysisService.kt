package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import com.capstone.backend.domain.video.entity.UserVideo
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
    private val userVideoRepository: UserVideoRepository
) {

    fun requestPitchingAnalysisAsync(userVideo: UserVideo, videoResource: Resource): Mono<List<AnalysisResult>> {

        val bodyBuilder = MultipartBodyBuilder()
        bodyBuilder.part("userVideo", videoResource)

        return pythonWebClient.post()
            .uri("/api/analysis")
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
                val userSkeleton = skeletonDataRepository.save(
                    SkeletonData(
                        skeletonData = userData.skeletonDataCsv,
                        frameCount = userData.frameCount,
                        fps = userData.fps,
                        resolution = userData.resolution,
                    )
                )

                userVideo.skeletonData = userSkeleton
                userVideoRepository.save(userVideo)

                val top3ProIds = userData.players.map { it.proId }
                val referenceModels = referenceModelRepository.findAllById(top3ProIds)

                val analysisResult = userData.players.map { playerDto ->
                    val matchedProModel = referenceModels.find { it.id == playerDto.proId }
                        ?: throw IllegalArgumentException("DB에 존재하지 않는 프로 선수 ID 반환됨: ${playerDto.proId}")
                    AnalysisResult(
                        similarityScore = playerDto.overallScore,
                        feedbackText = "분석 완료 (구간 수: ${playerDto.phaseScores.size})",
                        userVideo = userVideo,
                        referenceModel = matchedProModel
                    )
                }
                analysisResultRepository.saveAll(analysisResult)
            }
            .doOnError { error ->
                println("비동기 AI 분석 중 치명적 에러: ${error.message}")
            }

    }
    @Transactional(readOnly = true)
    fun getAllReferenceData(): List<ReferenceDataResponse>{
        val models = referenceModelRepository.findAll()
        return models.map{ model ->
            ReferenceDataResponse(
                proId = model.id!!,
                pitcherName = model.pitcherName,
                pitchType = model.pitchType,
                skeletonData = model.skeletonData.skeletonData
            )
        }
    }
}