package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.user.dto.GrowthPointResponse
import com.capstone.backend.domain.user.dto.MyAnalysisItemResponse
import com.capstone.backend.domain.user.dto.PitchDistributionResponse
import com.capstone.backend.domain.user.dto.ProSummaryResponse
import com.capstone.backend.domain.user.dto.UserProfileResponse
import com.capstone.backend.domain.user.dto.UserStatsResponse
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.UserVideoRepository
import jakarta.persistence.EntityNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import kotlin.math.roundToInt

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userVideoRepository: UserVideoRepository,
    private val analysisResultRepository: AnalysisResultRepository,
) {
    @Transactional(readOnly = true)
    fun getProfile(userId: Long): UserProfileResponse {
        val user = findUser(userId)
        val recent = completedVideos(user).count { it.isWithinRecentDays(30) }
        return UserProfileResponse(
            nickname = user.nickname,
            email = user.email,
            recentAnalysisCount = recent,
        )
    }

    @Transactional(readOnly = true)
    fun getStats(userId: Long): UserStatsResponse {
        val user = findUser(userId)
        // 완료된 영상마다 Top(최고 유사도) 결과를 뽑아 (영상, Top결과) 쌍으로 모은다.
        val pairs =
            completedVideos(user)
                .mapNotNull { video ->
                    val top = topResult(video) ?: return@mapNotNull null
                    video to top
                }

        val totalSessions = pairs.size
        val bestScore = pairs.maxOfOrNull { it.second.similarityScore }?.roundToInt() ?: 0
        val now = LocalDate.now()
        val thisMonth =
            pairs.count {
                val d = it.first.uploadedAt.toLocalDate()
                d.year == now.year && d.monthValue == now.monthValue
            }
        val recent = pairs.count { it.first.isWithinRecentDays(30) }

        // 성장 추이: 오래된 순으로 정렬, label "M/d", value = Top 유사도
        val growth =
            pairs
                .sortedBy { it.first.uploadedAt }
                .map { (video, top) ->
                    GrowthPointResponse(
                        label = "${video.uploadedAt.monthValue}/${video.uploadedAt.dayOfMonth}",
                        value = top.similarityScore.roundToInt(),
                    )
                }

        // 구종 분포: 사용자가 선택한 구종(영상) 기준 집계
        val pitchDistribution =
            pairs
                .groupingBy { it.first.pitchType ?: "직구" }
                .eachCount()
                .map { (type, count) ->
                    PitchDistributionResponse(
                        type = type,
                        count = count,
                        percentage = if (totalSessions > 0) (count * 100) / totalSessions else 0,
                    )
                }

        return UserStatsResponse(
            nickname = user.nickname,
            totalSessions = totalSessions,
            bestScore = bestScore,
            thisMonthSessions = thisMonth,
            recentAnalysisCount = recent,
            growth = growth,
            pitchDistribution = pitchDistribution,
        )
    }

    @Transactional(readOnly = true)
    fun getMyAnalyses(userId: Long): List<MyAnalysisItemResponse> {
        val user = findUser(userId)
        return completedVideos(user).mapNotNull { video ->
            val top = topResult(video) ?: return@mapNotNull null
            MyAnalysisItemResponse(
                videoId = video.id!!,
                date = video.uploadedAt.toLocalDate().toString().replace('-', '.'),
                playerName = top.referenceModel.pitcherName,
                pitchType = video.pitchType ?: "직구",
                similarity = top.similarityScore.roundToInt(),
            )
        }
    }

    // 내가 비교당한 프로 목록(중복 제거). 마이페이지 드롭다운용.
    @Transactional(readOnly = true)
    fun getComparedPros(userId: Long): List<ProSummaryResponse> =
        analysisResultRepository
            .findByUserVideo_User_Id(userId)
            .map { it.referenceModel }
            .distinctBy { it.id }
            .map { ProSummaryResponse(proId = it.id!!, pitcherName = it.pitcherName) }

    // 특정 프로에 대한 내 점수 변화 추이. 모든 분석 세션을 시간순(같은 날은 videoId 순)으로 점으로 찍는다.
    // 같은 날짜 라벨이 연속되면 첫 점에만 라벨을 달아 x축이 지저분하지 않게 한다. 마이페이지 프로별 그래프용.
    @Transactional(readOnly = true)
    fun getProGrowth(
        userId: Long,
        proId: Long,
    ): List<GrowthPointResponse> {
        val sorted =
            analysisResultRepository
                .findByUserVideo_User_IdAndReferenceModel_Id(userId, proId)
                .sortedWith(compareBy({ it.userVideo.uploadedAt }, { it.userVideo.id }))
        var lastLabel: String? = null
        return sorted.map {
            val dateLabel = "${it.userVideo.uploadedAt.monthValue}/${it.userVideo.uploadedAt.dayOfMonth}"
            val label = if (dateLabel == lastLabel) "" else dateLabel
            lastLabel = dateLabel
            GrowthPointResponse(label = label, value = it.similarityScore.roundToInt())
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────────
    private fun findUser(userId: Long): User =
        userRepository
            .findById(userId)
            .orElseThrow { EntityNotFoundException("사용자를 찾을 수 없습니다.") }

    // 완료된 영상만, 최신순(findAllBy...DescUploadedAt)
    private fun completedVideos(user: User): List<UserVideo> =
        userVideoRepository
            .findAllByUserOrderByUploadedAtDesc(user)
            .filter { it.status == "COMPLETED" }

    // 한 영상의 Top(최고 유사도) 분석 결과
    private fun topResult(video: UserVideo) =
        analysisResultRepository
            .findByUserVideoId(video.id!!)
            .maxByOrNull { it.similarityScore }

    private fun UserVideo.isWithinRecentDays(days: Long): Boolean =
        uploadedAt.toLocalDate().isAfter(LocalDate.now().minusDays(days))
}
