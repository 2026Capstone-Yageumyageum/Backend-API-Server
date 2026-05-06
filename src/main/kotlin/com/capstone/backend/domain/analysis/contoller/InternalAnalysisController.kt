package com.capstone.backend.domain.analysis.controller

import com.capstone.backend.domain.analysis.dto.ReferenceDataResponse
import com.capstone.backend.domain.analysis.service.AnalysisService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/internal/analysis")
class InternalAnalysisController(
    private val analysisService: AnalysisService
) {
    @GetMapping("/reference-models")
    fun getReferenceModelsForCache(): List<ReferenceDataResponse> {
        return analysisService.getAllReferenceData()
    }
}