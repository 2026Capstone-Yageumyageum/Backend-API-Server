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
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/reference-models")
    fun getReferenceModelsForCache(): List<ReferenceDataResponse> = analysisService.getAllReferenceData()

    @PostMapping("/test/dummy-video")
    fun createDummyVideo(): Map<String, Any> {
        val user =
            userRepository.save(
                User(
                    email = "test_${UUID.randomUUID()}@test.com",
                    nickname = "tester",
                ),
            )
        val video = userVideoRepository.save(UserVideo(user = user, videoUrl = "dummy.mp4"))
        return mapOf(
            "message" to "더미 비디오가 생성되었습니다.",
            "userId" to user.id!!,
            "videoId" to video.id!!,
        )
    }

    @PostMapping("/test/dummy-reference")
    fun createDummyReference(): Map<String, Any> {
        val skeleton =
            skeletonDataRepository.save(
                SkeletonData(
                    skeletonData = "{}",
                    frameCount = 300,
                    fps = 30.0,
                    resolution = "1920x1080",
                ),
            )
        val referenceModel =
            referenceModelRepository.save(
                ReferenceModel(
                    pitcherName =
                        "테스트선수_${
                            UUID.randomUUID()
                                .toString()
                                .substring(0, 5)
                        }",
                    pitchType = "직구",
                    sourceUrl = "dummy_pro.mp4",
                    skeletonData = skeleton,
                ),
            )
        return mapOf(
            "message" to "더미 레퍼런스 모델이 생성되었습니다.",
            "referenceId" to referenceModel.id!!,
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
            val objectMapper = ObjectMapper()
            val root = objectMapper.readTree(skeletonContent)
            if (root.isArray && root.size() > 0) {
                val firstObj = root.get(0)
                if (firstObj.has("skeleton_data")) {
                    finalSkeletonDataCsv = firstObj.get("skeleton_data").asText()
                }
            } else if (root.isObject && root.has("skeleton_data")) {
                finalSkeletonDataCsv = root.get("skeleton_data").asText()
            }
        } catch (e: JacksonException) {
            // JSON이 아니면 CSV 원본으로 간주하고 그대로 사용한다(정상 경로).
            // 다만 원인을 삼키면 파일 형식 문제를 영영 알 수 없으므로 로그는 남긴다.
            log.warn("스켈레톤 파일을 JSON으로 해석하지 못해 원본을 그대로 사용합니다: {}", skeletonFile.originalFilename, e)
        }

        val skeleton =
            skeletonDataRepository.save(
                SkeletonData(
                    skeletonData = finalSkeletonDataCsv,
                    frameCount = frameCount,
                    fps = fps,
                    resolution = resolution,
                ),
            )
        val referenceModel =
            referenceModelRepository.save(
                ReferenceModel(
                    pitcherName = pitcherName,
                    pitchType = pitchType,
                    sourceUrl = sourceUrl,
                    skeletonData = skeleton,
                ),
            )
        return mapOf(
            "message" to "레퍼런스 모델이 성공적으로 저장되었습니다.",
            "referenceId" to referenceModel.id!!,
        )
    }

    @PostMapping("/reference-models/bulk", consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE])
    fun bulkUploadReferenceModels(
        @org.springframework.web.bind.annotation.RequestPart("jsonFile") jsonFile: org.springframework.web.multipart.MultipartFile,
    ): Map<String, Any> {
        val content = String(jsonFile.bytes, Charsets.UTF_8)
        val objectMapper = ObjectMapper()
        val root = objectMapper.readTree(content)

        // 최상위가 배열이면 그대로, 객체면 items/proSkeletonData/... 안의 배열을 사용
        val items =
            when {
                root.isArray -> root
                root.isObject ->
                    listOf("items", "proSkeletonData", "pro_skeleton_data", "players", "data")
                        .firstNotNullOfOrNull { key -> root.get(key)?.takeIf { it.isArray } }
                        ?: throw BusinessException(ErrorCode.INVALID_JSON_FORMAT, "JSON 객체에서 items 배열을 찾지 못했습니다.")
                else -> throw BusinessException(ErrorCode.INVALID_JSON_FORMAT)
            }

        var successCount = 0
        for (node in items) {
            val playerName = node.get("playerName")?.asText() ?: "알 수 없음"
            // camelCase(skeletonData) / snake_case(skeleton_data) / keypointsCsvText 모두 허용
            val skeletonDataCsv =
                (
                    node.get("skeletonData")
                        ?: node.get("skeleton_data")
                        ?: node.get("keypointsCsvText")
                )?.asText() ?: continue
            val pitchType = node.get("pitchType")?.asText() ?: "직구" // 없으면 기본값 직구

            // metadata에 실제 값이 있으면 사용, 없으면 fallback
            val metadata = node.get("metadata")?.takeIf { it.isObject }
            val frameCount =
                metadata?.get("frameCount")?.takeIf { it.isNumber }?.asInt()
                    ?: run {
                        // CSV 라인 수로 프레임 수 자동 계산 (header 1줄 제외)
                        val lines = skeletonDataCsv.split("\n", "\r\n").filter { it.isNotBlank() }
                        if (lines.isNotEmpty()) lines.size - 1 else 0
                    }
            val fps = metadata?.get("fps")?.takeIf { it.isNumber }?.asDouble() ?: 60.0
            val resolution = metadata?.get("resolution")?.asText()?.takeIf { it.isNotBlank() } ?: "1920x1080"

            val skeleton =
                skeletonDataRepository.save(
                    SkeletonData(
                        skeletonData = skeletonDataCsv,
                        frameCount = frameCount,
                        fps = fps,
                        resolution = resolution,
                    ),
                )
            referenceModelRepository.save(
                ReferenceModel(
                    pitcherName = playerName,
                    pitchType = pitchType,
                    sourceUrl = node.get("proId")?.asText(),
                    skeletonData = skeleton,
                ),
            )
            successCount++
        }

        return mapOf(
            "message" to "총 ${successCount}명의 프로 선수 데이터가 성공적으로 일괄 등록되었습니다!",
        )
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/reference-models/clear")
    fun clearAllReferenceData(): Map<String, Any> {
        referenceModelRepository.deleteAll()
        skeletonDataRepository.deleteAll()
        return mapOf("message" to "기존 레퍼런스 및 스켈레톤 데이터가 모두 삭제되었습니다.")
    }
}
