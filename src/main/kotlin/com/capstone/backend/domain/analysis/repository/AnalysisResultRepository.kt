package com.capstone.backend.domain.analysis.repository

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.video.entity.UserVideo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AnalysisResultRepository : JpaRepository<AnalysisResult, Long> {
    fun findAllByUserVideo(userVideo: UserVideo): List<AnalysisResult>
}
