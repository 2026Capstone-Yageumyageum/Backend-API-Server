package com.capstone.backend.domain.analysis.repository

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AnalysisResultRepository : JpaRepository<AnalysisResult, Long> {
    fun findByUserVideoId(userVideoId: Long): List<AnalysisResult>
}
