package com.capstone.backend.domain.analysis.controller

import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.entity.ReferenceModel
import com.capstone.backend.domain.analysis.repository.ReferenceModelRepository
import com.capstone.backend.domain.analysis.service.AnalysisService
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.domain.video.repository.UserVideoRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/internal/analysis")
class InternalAnalysisController(
    private val analysisService: AnalysisService,
    private val userRepository: UserRepository,
    private val userVideoRepository: UserVideoRepository,
    private val skeletonDataRepository: SkeletonDataRepository,
    private val referenceModelRepository: ReferenceModelRepository,
) {
    @GetMapping("/reference-models")
    fun getReferenceModelsForCache(): List<ReferenceDataResponse> = analysisService.getAllReferenceData()

    @PostMapping("/test/dummy-video")
    fun createDummyVideo(): Map<String, Any> {
        val user = userRepository.save(User(email = "test_${UUID.randomUUID()}@test.com", nickname = "tester"))
        val video = userVideoRepository.save(UserVideo(user = user, videoUrl = "dummy.mp4"))
        return mapOf(
            "message" to "더미 비디오가 생성되었습니다.",
            "userId" to user.id!!,
            "videoId" to video.id!!
        )
    }

    @PostMapping("/test/dummy-reference")
    fun createDummyReference(): Map<String, Any> {
        val skeleton = skeletonDataRepository.save(
            SkeletonData(
                skeletonData = "{}",
                frameCount = 300,
                fps = 30.0,
                resolution = "1920x1080"
            )
        )
        val referenceModel = referenceModelRepository.save(
            ReferenceModel(
                pitcherName = "테스트선수_${UUID.randomUUID().toString().substring(0, 5)}",
                pitchType = "직구",
                sourceUrl = "dummy_pro.mp4",
                skeletonData = skeleton
            )
        )
        return mapOf(
            "message" to "더미 레퍼런스 모델이 생성되었습니다.",
            "referenceId" to referenceModel.id!!
        )
    }

    @PostMapping("/reference-model", consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createReferenceModel(
        @org.springframework.web.bind.annotation.RequestParam pitcherName: String,
        @org.springframework.web.bind.annotation.RequestParam pitchType: String,
        @org.springframework.web.bind.annotation.RequestParam(required = false) sourceUrl: String?,
        @org.springframework.web.bind.annotation.RequestParam frameCount: Int,
        @org.springframework.web.bind.annotation.RequestParam fps: Double,
        @org.springframework.web.bind.annotation.RequestParam(required = false) resolution: String?,
        @org.springframework.web.bind.annotation.RequestPart("skeletonFile") skeletonFile: org.springframework.web.multipart.MultipartFile,
    ): Map<String, Any> {
        val skeletonContent = String(skeletonFile.bytes, Charsets.UTF_8)
        
        var finalSkeletonDataCsv = skeletonContent
        try {
            val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
            val root = objectMapper.readTree(skeletonContent)
            if (root.isArray && root.size() > 0) {
                val firstObj = root.get(0)
                if (firstObj.has("skeleton_data")) {
                    finalSkeletonDataCsv = firstObj.get("skeleton_data").asText()
                }
            } else if (root.isObject && root.has("skeleton_data")) {
                finalSkeletonDataCsv = root.get("skeleton_data").asText()
            }
        } catch (e: Exception) {
            // JSON이 아니면 그냥 원본 사용
        }
        
        val skeleton = skeletonDataRepository.save(
            SkeletonData(
                skeletonData = finalSkeletonDataCsv,
                frameCount = frameCount,
                fps = fps,
                resolution = resolution
            )
        )
        val referenceModel = referenceModelRepository.save(
            ReferenceModel(
                pitcherName = pitcherName,
                pitchType = pitchType,
                sourceUrl = sourceUrl,
                skeletonData = skeleton
            )
        )
        return mapOf(
            "message" to "레퍼런스 모델이 성공적으로 저장되었습니다.",
            "referenceId" to referenceModel.id!!
        )
    }

    @PostMapping("/reference-models/bulk", consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE])
    fun bulkUploadReferenceModels(
        @org.springframework.web.bind.annotation.RequestPart("jsonFile") jsonFile: org.springframework.web.multipart.MultipartFile,
    ): Map<String, Any> {
        val content = String(jsonFile.bytes, Charsets.UTF_8)
        val objectMapper = com.fasterxml.jackson.databind.ObjectMapper()
        val rootArray = objectMapper.readTree(content)
        
        if (!rootArray.isArray) {
            throw IllegalArgumentException("파일이 JSON 배열 형식이 아닙니다.")
        }
        
        var successCount = 0
        for (node in rootArray) {
            val playerName = node.get("playerName")?.asText() ?: "알 수 없음"
            val skeletonDataCsv = node.get("skeleton_data")?.asText() ?: continue
            val pitchType = node.get("pitchType")?.asText() ?: "직구" // 없으면 기본값 직구
            
            // CSV 라인 수로 프레임 수 자동 계산
            val lines = skeletonDataCsv.split("\n", "\r\n").filter { it.isNotBlank() }
            val frameCount = if (lines.isNotEmpty()) lines.size - 1 else 0
            
            val skeleton = skeletonDataRepository.save(
                SkeletonData(
                    skeletonData = skeletonDataCsv,
                    frameCount = frameCount,
                    fps = 60.0, // 기본값
                    resolution = "1920x1080"
                )
            )
            referenceModelRepository.save(
                ReferenceModel(
                    pitcherName = playerName,
                    pitchType = pitchType,
                    sourceUrl = node.get("proId")?.asText(),
                    skeletonData = skeleton
                )
            )
            successCount++
        }
        
        return mapOf(
            "message" to "총 \${successCount}명의 프로 선수 데이터가 성공적으로 일괄 등록되었습니다!"
        )
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/reference-models/clear")
    fun clearAllReferenceData(): Map<String, Any> {
        referenceModelRepository.deleteAll()
        skeletonDataRepository.deleteAll()
        return mapOf("message" to "기존 레퍼런스 및 스켈레톤 데이터가 모두 삭제되었습니다.")
    }
}
