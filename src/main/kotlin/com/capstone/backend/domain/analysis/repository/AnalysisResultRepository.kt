package com.capstone.backend.domain.analysis.repository

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AnalysisResultRepository : JpaRepository<AnalysisResult, Long> {
    fun findByUserVideoId(userVideoId: Long): List<AnalysisResult>

    // 특정 사용자의, 특정 프로(referenceModel)에 대한 모든 분석 결과 (프로별 점수 추이용)
    fun findByUserVideo_User_IdAndReferenceModel_Id(
        userId: Long,
        referenceModelId: Long,
    ): List<AnalysisResult>

    // 특정 사용자의 모든 분석 결과 (비교된 프로 목록 추출용)
    fun findByUserVideo_User_Id(userId: Long): List<AnalysisResult>

    // 특정 "최고의 1구" 영상과 비교된 모든 기록 (일관성 탭 카드 펼침 목록용)
    fun findByBestPitchVideo_IdOrderByCreatedAtDesc(bestPitchVideoId: Long): List<AnalysisResult>
}
