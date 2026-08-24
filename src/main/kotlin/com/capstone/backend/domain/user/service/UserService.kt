package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.service.COMPARISON_PRO
import com.capstone.backend.domain.analysis.service.DEFAULT_PITCH_TYPE
import com.capstone.backend.domain.analysis.service.VIDEO_STATUS_COMPLETED
import com.capstone.backend.domain.user.dto.BestPitchCardResponse
import com.capstone.backend.domain.user.dto.BestPitchComparisonItemResponse
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
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
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
        val pairs = videosWithTopResult(user)

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
                .groupingBy { it.first.pitchType ?: DEFAULT_PITCH_TYPE }
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
        return videosWithTopResult(user).map { (video, top) ->
            MyAnalysisItemResponse(
                videoId = video.requireId(),
                date = video.formattedDate(),
                playerName = top.referenceModel?.pitcherName ?: "프로",
                pitchType = video.pitchType ?: DEFAULT_PITCH_TYPE,
                similarity = top.similarityScore.roundToInt(),
            )
        }
    }

    // 내가 비교당한 프로 목록(중복 제거). 마이페이지 드롭다운용.
    @Transactional(readOnly = true)
    fun getComparedPros(userId: Long): List<ProSummaryResponse> =
        analysisResultRepository
            .findByUserVideo_User_Id(userId)
            .mapNotNull { it.referenceModel }
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

    /**
     * 일관성 탭: 사용자가 등록한 구종별 "최고의 1구" 카드 목록.
     *
     * 예전에는 best 영상마다 비교 기록을 따로 조회해 카드 수만큼 쿼리가 나갔다.
     * 지금은 한 번에 가져와 메모리에서 묶는다.
     */
    @Transactional(readOnly = true)
    fun getBestPitches(userId: Long): List<BestPitchCardResponse> {
        val user = findUser(userId)
        val bestVideos = userVideoRepository.findAllByUserAndIsBestPitchTrue(user)
        if (bestVideos.isEmpty()) return emptyList()

        val scoresByBestId =
            analysisResultRepository
                .findByBestPitchVideo_IdIn(bestVideos.map { it.requireId() })
                .groupBy({ it.bestPitchVideo?.id }, { it.similarityScore })

        return bestVideos.map { best ->
            val scores = scoresByBestId[best.id].orEmpty()
            BestPitchCardResponse(
                videoId = best.requireId(),
                pitchType = best.pitchType ?: DEFAULT_PITCH_TYPE,
                date = best.formattedDate(),
                bestConsistency = scores.maxOrNull()?.roundToInt() ?: 0,
                sessionCount = scores.size,
                avgConsistency = if (scores.isNotEmpty()) scores.average().roundToInt() else 0,
            )
        }
    }

    // 카드 펼침: 특정 구종의 최고의 1구와 비교된 내 기록 목록(최신순).
    @Transactional(readOnly = true)
    fun getBestPitchComparisons(
        userId: Long,
        pitchType: String,
    ): List<BestPitchComparisonItemResponse> {
        val user = findUser(userId)
        val best =
            userVideoRepository.findFirstByUserAndPitchTypeAndIsBestPitchTrue(user, pitchType)
                ?: return emptyList()
        return analysisResultRepository.findByBestPitchVideo_IdOrderByCreatedAtDesc(best.requireId()).map {
            BestPitchComparisonItemResponse(
                videoId = it.userVideo.requireId(),
                bestPitchVideoId = best.requireId(),
                date = it.userVideo.formattedDate(),
                pitchType = pitchType,
                consistency = it.similarityScore.roundToInt(),
            )
        }
    }

    // ── 내부 헬퍼 ──────────────────────────────────────────────────────────────
    private fun findUser(userId: Long): User =
        userRepository
            .findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

    // 완료된 영상만, 최신순(findAllBy...DescUploadedAt)
    private fun completedVideos(user: User): List<UserVideo> =
        userVideoRepository
            .findAllByUserOrderByUploadedAtDesc(user)
            .filter { it.status == VIDEO_STATUS_COMPLETED }

    /**
     * 완료된 영상마다 Top(최고 유사도) 프로 비교 결과를 붙여 (영상, Top결과) 쌍으로 돌려준다.
     *
     * 예전에는 영상 하나당 findByUserVideoId를 호출해 영상이 50개면 쿼리가 51번 나갔다(N+1).
     * 지금은 영상 목록 1회 + 결과 목록 1회, 총 2회로 끝난다.
     * 최고의 1구(BEST_PITCH) 비교는 프로 통계에서 제외한다.
     */
    private fun videosWithTopResult(user: User): List<Pair<UserVideo, AnalysisResult>> {
        val videos = completedVideos(user)
        if (videos.isEmpty()) return emptyList()

        val topByVideoId =
            analysisResultRepository
                .findByUserVideoIdIn(videos.map { it.requireId() })
                .filter { it.comparisonType == COMPARISON_PRO }
                .groupBy { it.userVideo.id }
                .mapValues { (_, results) -> results.maxByOrNull { it.similarityScore } }

        return videos.mapNotNull { video ->
            topByVideoId[video.id]?.let { top -> video to top }
        }
    }

    private fun UserVideo.isWithinRecentDays(days: Long): Boolean = uploadedAt.toLocalDate().isAfter(LocalDate.now().minusDays(days))

    /** 저장된 엔티티의 id는 항상 존재한다. !! 대신 실패 이유가 드러나는 메시지를 남긴다. */
    private fun UserVideo.requireId(): Long = id ?: error("저장되지 않은 UserVideo입니다.")

    private fun UserVideo.formattedDate(): String =
        uploadedAt
            .toLocalDate()
            .toString()
            .replace('-', '.')
}
