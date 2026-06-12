package com.capstone.backend.domain.analysis.contoller

import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.service.AnalysisService
import org.springframework.core.io.FileSystemResource
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.nio.file.Files

@RestController
@RequestMapping("/api/analysis")
class AnalysisController(
    private val analysisService: AnalysisService,
) {
    // 원샷 분석 API: 앱이 영상 파일을 한 번에 올리면 UserVideo를 생성하고 videoId를 즉시 반환한 뒤
    // 백그라운드에서 파이썬 분석을 수행한다. 앱은 반환된 videoId로 결과를 폴링한다.
    @PostMapping(consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyzeNewVideo(
        @AuthenticationPrincipal userId: Long?,
        @RequestPart("file") file: MultipartFile,
        @org.springframework.web.bind.annotation.RequestParam(required = false) pitchType: String?,
    ): ResponseEntity<Map<String, Any>> {
        val resolvedUserId = userId ?: throw IllegalArgumentException("로그인이 필요합니다.")

        // 요청 종료 후에도 백그라운드 스레드가 읽을 수 있도록 업로드 파일을 임시 파일로 복사한다.
        val suffix =
            file.originalFilename
                ?.substringAfterLast('.', "")
                ?.takeIf { it.isNotBlank() }
                ?.let { ".$it" } ?: ".mp4"
        val tempFile = Files.createTempFile("pitch_upload_", suffix)
        file.transferTo(tempFile)

        val video =
            analysisService.createPendingVideo(
                resolvedUserId,
                file.originalFilename,
                pitchType?.takeIf { it.isNotBlank() } ?: "직구",
            )

        analysisService
            .requestPitchingAnalysisAsync(video.id!!, FileSystemResource(tempFile))
            .doFinally { Files.deleteIfExists(tempFile) }
            .subscribeOn(Schedulers.boundedElastic())
            .subscribe()

        return ResponseEntity.accepted().body(
            mapOf(
                "videoId" to video.id!!,
                "status" to "PENDING",
                "message" to "분석을 시작했습니다. 결과는 잠시 후 조회해 주세요.",
            ),
        )
    }

    @PostMapping("/{videoId}", consumes = [org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE]) // 분석 요청 API
    fun analysisPitching(
        @PathVariable videoId: Long,
        @RequestPart("file") file: MultipartFile,
    ): Mono<ResponseEntity<Map<String, String>>> =
        analysisService
            .requestPitchingAnalysisAsync(videoId, file.resource)
            .map {
                ResponseEntity.accepted().body(mapOf("message" to "분석 요청이 수락되었습니다. 잠시만 기다려주세요"))
            }

    @GetMapping("/{videoId}/result") // 분석 결과 조회 API
    fun getAnalysisResult(
        @PathVariable videoId: Long,
    ): ResponseEntity<AnalysisResultResponse> = ResponseEntity.ok(analysisService.getAnalysisResult(videoId))

    @GetMapping("/{videoId}/skeleton") // 스켈레톤 데이터 조회 API
    fun getSkeletonData(
        @PathVariable videoId: Long,
    ): ResponseEntity<Map<String, Any>> = ResponseEntity.ok(analysisService.getSkeletonData(videoId))

    @GetMapping("/reference-data")
    fun getAllReferenceData(): ResponseEntity<List<ReferenceDataResponse>> = ResponseEntity.ok(analysisService.getAllReferenceData())
}
