package com.capstone.backend.domain.analysis.controller

import com.capstone.backend.domain.analysis.dto.BulkUploadResponse
import com.capstone.backend.domain.analysis.dto.MessageResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.dto.ReferenceModelSaveResponse
import com.capstone.backend.domain.analysis.service.ReferenceModelService
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 서버 간 통신용 내부 API. 앱 클라이언트는 호출하지 않는다.
 *
 * 접근 통제는 SecurityConfig의 internalApiFilterChain이 출발지 IP로 수행한다.
 * 파이썬 분석 서버가 인증 헤더 없이 레퍼런스 목록을 가져가기 때문이다.
 */
@RestController
@RequestMapping("/api/internal/analysis")
class InternalAnalysisController(
    private val referenceModelService: ReferenceModelService,
) {
    /** 파이썬 분석 서버가 프로 골격 캐시를 채울 때 호출한다. */
    @GetMapping("/reference-models")
    fun getReferenceModelsForCache(): List<ReferenceDataResponse> = referenceModelService.findAll()

    @PostMapping("/reference-model", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createReferenceModel(
        @RequestParam pitcherName: String,
        @RequestParam pitchType: String,
        @RequestParam(required = false) sourceUrl: String?,
        @RequestParam frameCount: Int,
        @RequestParam fps: Double,
        @RequestParam(required = false) resolution: String?,
        @RequestPart("skeletonFile") skeletonFile: MultipartFile,
    ): ReferenceModelSaveResponse {
        val referenceId =
            referenceModelService.create(
                pitcherName = pitcherName,
                pitchType = pitchType,
                sourceUrl = sourceUrl,
                frameCount = frameCount,
                fps = fps,
                resolution = resolution,
                // bytes 대신 inputStream을 넘긴다. 파일 전체를 ByteArray로 복사하지 않는다.
                skeletonFile = skeletonFile.inputStream,
                fileName = skeletonFile.originalFilename,
            )
        return ReferenceModelSaveResponse(referenceId, "레퍼런스 모델이 저장되었습니다.")
    }

    @PostMapping("/reference-models/bulk", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun bulkUploadReferenceModels(
        @RequestPart("jsonFile") jsonFile: MultipartFile,
    ): BulkUploadResponse = referenceModelService.bulkUpload(jsonFile.inputStream)

    @DeleteMapping("/reference-models/clear")
    fun clearAllReferenceData(): MessageResponse {
        referenceModelService.deleteAll()
        return MessageResponse("기존 레퍼런스 및 스켈레톤 데이터가 모두 삭제되었습니다.")
    }
}
