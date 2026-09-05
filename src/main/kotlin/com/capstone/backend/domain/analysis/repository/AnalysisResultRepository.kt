package com.capstone.backend.domain.analysis.repository

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

// Spring Data JPA에서 밑줄(_)은 연관관계 탐색 경계를 명시하는 구분자다.
// 예: findByUserVideo_User_Id → userVideo.user.id
// 밑줄을 빼면 Spring이 프로퍼티 경로를 잘못 추론할 수 있으므로,
// camelCase를 요구하는 ktlint 규칙을 이 인터페이스에서만 끈다.
@Suppress("ktlint:standard:function-naming")
@Repository
interface AnalysisResultRepository : JpaRepository<AnalysisResult, Long> {
    fun findByUserVideoId(userVideoId: Long): List<AnalysisResult>

    // 여러 영상의 결과를 한 번에 가져온다. 영상마다 findByUserVideoId를 부르면
    // 영상 수만큼 쿼리가 나가므로(N+1), 목록 화면에서는 반드시 이쪽을 쓴다.
    fun findByUserVideoIdIn(userVideoIds: Collection<Long>): List<AnalysisResult>

    // 여러 "최고의 1구"에 대한 비교 기록을 한 번에 가져온다(일관성 탭 카드 목록용).
    fun findByBestPitchVideo_IdIn(bestPitchVideoIds: Collection<Long>): List<AnalysisResult>

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
