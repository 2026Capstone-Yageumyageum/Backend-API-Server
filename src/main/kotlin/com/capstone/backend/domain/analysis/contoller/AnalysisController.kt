package com.capstone.backend.domain.analysis.contoller

import com.capstone.backend.domain.analysis.dto.AnalysisResultResponse
import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.service.AnalysisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/analysis")
class AnalysisController(
    private val analysisService: AnalysisService,
) {
    @PostMapping("/{videoId}") // 분석 요청 API
    fun analysisPitching(
        @PathVariable videoId: Long,
        @RequestPart("file") file: MultipartFile,
    ): Mono<ResponseEntity<Map<String, String>>> {
        return analysisService
            .requestPitchingAnalysisAsync(videoId, file.resource)
            .map {
                ResponseEntity.accepted().body(mapOf("message" to "분석 요청이 수락되었습니다. 잠시만 기다려주세요"))
            }
    }
    @GetMapping("/{videoId}/result") // 분석 결과 조회 API
    fun getAnalysisResult(@PathVariable videoId: Long): ResponseEntity<AnalysisResultResponse> {
        return ResponseEntity.ok(analysisService.getAnalysisResult(videoId))
    }
    @GetMapping("/{videoId}/skeleton") // 스켈레톤 데이터 조회 API
    fun getSkeletonData(@PathVariable videoId: Long): ResponseEntity<Map<String, Any>> {
        return ResponseEntity.ok(analysisService.getSkeletonData(videoId))
    }

    @GetMapping("/reference-data")
    fun getAllReferenceData(): ResponseEntity<List<ReferenceDataResponse>> {
        return ResponseEntity.ok(analysisService.getAllReferenceData())
    }
}
