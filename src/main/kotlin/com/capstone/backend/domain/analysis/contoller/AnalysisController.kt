package com.capstone.backend.domain.analysis.contoller

import com.capstone.backend.domain.analysis.service.AnalysisService
import com.capstone.backend.domain.video.repository.UserVideoRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.multipart.MultipartFile
import reactor.core.publisher.Mono

@RestController
@RequestMapping("/api/analysis")
class AnalysisController (
    private val analysisService: AnalysisService,
    private val userVideoRepository: UserVideoRepository
){
    @PostMapping("/{videoId}")
    fun analysisPitching(
        @PathVariable videoId: Long,
        @RequestPart("file") file: MultipartFile
    ): Mono<ResponseEntity<Map<String, String>>> {
        val userVideo = userVideoRepository.findById(videoId)
            .orElseThrow{ EntityNotFoundException("해당 영상 정보를 찾을 수 없습니다") }
        return analysisService.requestPitchingAnalysisAsync(userVideo, file.resource)
            .map{
                ResponseEntity.accepted().body(mapOf("message" to "분석 요청이 수락되었습니다. 잠시만 기다려주세요"))
            }
    }
}