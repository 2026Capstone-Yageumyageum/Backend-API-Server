package com.capstone.backend.domain.analysis.controller

import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.AnalysisStartResponse
import com.capstone.backend.domain.analysis.dto.BestPitchRegisterResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.dto.SkeletonDataResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.service.AnalysisService
import com.capstone.backend.domain.analysis.service.DEFAULT_PITCH_TYPE
import com.capstone.backend.domain.analysis.service.ReferenceModelService
import com.capstone.backend.domain.analysis.service.VIDEO_STATUS_PENDING
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.file.Files

/** 확장자를 알 수 없을 때 쓰는 기본값. 파이썬 서버가 컨테이너 포맷을 추론하는 데 쓰인다. */
private const val DEFAULT_VIDEO_SUFFIX = ".mp4"

@RestController
@RequestMapping("/api/analysis")
class AnalysisController(
    private val analysisService: AnalysisService,
    private val referenceModelService: ReferenceModelService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 원샷 분석 API: 영상을 올리면 UserVideo를 만들고 videoId를 즉시 돌려준 뒤
     * 백그라운드에서 파이썬 분석을 수행한다. 앱은 이 videoId로 결과를 폴링한다.
     */
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyzeNewVideo(
        @AuthenticationPrincipal userId: Long?,
        @RequestPart("file") file: MultipartFile,
        @RequestParam(required = false) pitchType: String?,
        @RequestParam(required = false) startSec: Double?,
        @RequestParam(required = false) endSec: Double?,
    ): ResponseEntity<AnalysisStartResponse> =
        startAnalysis(userId, file, pitchType, "분석을 시작했습니다. 결과는 잠시 후 조회해 주세요.") { videoId, resource ->
            analysisService.requestPitchingAnalysisAsync(videoId, resource, startSec, endSec)
        }

    /**
     * 최고의 1구 비교 분석: 새 영상을 올리면서 비교 대상(bestPitchVideoId)을 함께 받는다.
     * analyzeNewVideo와 흐름이 같고, 프로 대신 내 최고의 1구 골격을 레퍼런스로 쓴다.
     */
    @PostMapping("/best-pitch", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyzeBestPitchComparison(
        @AuthenticationPrincipal userId: Long?,
        @RequestPart("file") file: MultipartFile,
        @RequestParam bestPitchVideoId: Long,
        @RequestParam(required = false) pitchType: String?,
        @RequestParam(required = false) startSec: Double?,
        @RequestParam(required = false) endSec: Double?,
    ): ResponseEntity<AnalysisStartResponse> =
        startAnalysis(userId, file, pitchType, "최고의 1구 비교 분석을 시작했습니다. 결과는 잠시 후 조회해 주세요.") { videoId, resource ->
            analysisService.requestBestPitchAnalysisAsync(videoId, resource, bestPitchVideoId, startSec, endSec)
        }

    /**
     * 업로드를 받아 PENDING 영상을 만들고 백그라운드 분석을 띄우는 공통 흐름.
     *
     * 임시 파일을 쓰는 이유: MultipartFile은 요청이 끝나면 정리되므로,
     * 응답을 먼저 보내고 나중에 읽는 백그라운드 작업에서는 접근할 수 없다.
     */
    private fun startAnalysis(
        userId: Long?,
        file: MultipartFile,
        pitchType: String?,
        message: String,
        analyze: (videoId: Long, resource: Resource) -> Mono<List<AnalysisResult>>,
    ): ResponseEntity<AnalysisStartResponse> {
        val resolvedUserId = userId ?: throw BusinessException(ErrorCode.LOGIN_REQUIRED)

        val tempFile = Files.createTempFile("pitch_upload_", resolveSuffix(file))
        val videoId =
            try {
                file.transferTo(tempFile)
                analysisService
                    .createPendingVideo(
                        resolvedUserId,
                        file.originalFilename,
                        pitchType?.takeIf { it.isNotBlank() } ?: DEFAULT_PITCH_TYPE,
                    ).id!!
            } catch (e: Exception) {
                // 분석을 시작하기 전에 실패하면 doFinally에 도달하지 못한다.
                // 500MB짜리 임시 파일이 디스크에 그대로 남으므로 여기서 직접 지운다.
                Files.deleteIfExists(tempFile)
                throw e
            }

        analyze(videoId, FileSystemResource(tempFile))
            .doFinally { Files.deleteIfExists(tempFile) }
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe(
                { },
                // 에러 콜백이 없으면 예외가 Reactor의 onErrorDropped로 흘러가 흔적 없이 사라진다.
                // 영상 상태는 서비스가 FAILED로 바꾸므로, 여기서는 원인을 남기는 것이 목적이다.
                { error -> log.error("백그라운드 분석이 실패했습니다. videoId={}", videoId, error) },
            )

        return ResponseEntity.accepted().body(
            AnalysisStartResponse(videoId = videoId, status = VIDEO_STATUS_PENDING, message = message),
        )
    }

    private fun resolveSuffix(file: MultipartFile): String =
        file.originalFilename
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            ?: DEFAULT_VIDEO_SUFFIX

    // 영상을 "최고의 1구"로 등록한다(구종당 1개).
    @PostMapping("/{videoId}/best-pitch")
    fun registerBestPitch(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable videoId: Long,
    ): ResponseEntity<BestPitchRegisterResponse> {
        val resolvedUserId = userId ?: throw BusinessException(ErrorCode.LOGIN_REQUIRED)
        analysisService.registerBestPitch(resolvedUserId, videoId)
        return ResponseEntity.ok(BestPitchRegisterResponse(videoId, "최고의 1구로 등록되었습니다."))
    }

    @GetMapping("/{videoId}/result") // 분석 결과 조회 API
    fun getAnalysisResult(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable videoId: Long,
    ): ResponseEntity<AnalysisResultResponse> {
        val resolvedUserId = userId ?: throw BusinessException(ErrorCode.LOGIN_REQUIRED)
        return ResponseEntity.ok(analysisService.getAnalysisResult(resolvedUserId, videoId))
    }

    @GetMapping("/{videoId}/skeleton") // 스켈레톤 데이터 조회 API
    fun getSkeletonData(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable videoId: Long,
    ): ResponseEntity<SkeletonDataResponse> {
        val resolvedUserId = userId ?: throw BusinessException(ErrorCode.LOGIN_REQUIRED)
        return ResponseEntity.ok(analysisService.getSkeletonData(resolvedUserId, videoId))
    }

    @GetMapping("/reference-data")
    fun getAllReferenceData(): ResponseEntity<List<ReferenceDataResponse>> = ResponseEntity.ok(referenceModelService.findAll())
}
