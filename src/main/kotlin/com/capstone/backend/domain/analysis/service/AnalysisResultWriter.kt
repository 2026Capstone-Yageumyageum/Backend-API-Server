package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.video.entity.SkeletonData
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.domain.video.repository.UserVideoRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

const val VIDEO_STATUS_PENDING = "PENDING"
const val VIDEO_STATUS_COMPLETED = "COMPLETED"
const val VIDEO_STATUS_FAILED = "FAILED"

/**
 * 분석 결과를 DB에 반영하는 책임만 가진다.
 *
 * AnalysisService 안의 메서드로 두지 않고 별도 빈으로 분리한 이유:
 * Spring의 @Transactional은 AOP 프록시로 동작하므로, 같은 클래스 안에서 자기 메서드를
 * 직접 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다(self-invocation).
 * 기존 코드는 Mono의 map 블록 안에서 스켈레톤·결과·상태를 각각 저장했는데,
 * 트랜잭션이 없어 중간에 실패하면 스켈레톤만 남는 고아 데이터가 생겼다.
 */
@Component
class AnalysisResultWriter(
    private val userVideoRepository: UserVideoRepository,
    private val skeletonDataRepository: SkeletonDataRepository,
    private val analysisResultRepository: AnalysisResultRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 스켈레톤 저장 → 결과 저장 → 상태 전환을 하나의 트랜잭션으로 묶는다.
     *
     * 상태를 COMPLETED로 바꾸는 것은 결과 행을 모두 저장한 '뒤'다.
     * 그래야 폴링(getAnalysisResult)이 COMPLETED를 보는 순간 결과도 반드시 존재한다.
     * 예전에는 상태를 먼저 커밋해, 그 사이에 폴링이 들어오면 결과 0건을 받아
     * 프론트가 목업으로 폴백되는 간헐적 레이스가 있었다.
     *
     * @param buildResults 트랜잭션 안에서 실행된다. 연관 엔티티를 여기서 조회해야
     *                     detached 엔티티를 참조하는 문제를 피할 수 있다.
     */
    @Transactional
    fun saveSuccess(
        videoId: Long,
        response: AnalysisResponse,
        buildResults: (UserVideo, AnalysisResponse) -> List<AnalysisResult>,
    ): List<AnalysisResult> {
        val userVideo =
            userVideoRepository
                .findById(videoId)
                .orElseThrow { BusinessException(ErrorCode.VIDEO_NOT_FOUND) }

        val userData = response.userData
        userVideo.skeletonData =
            skeletonDataRepository.save(
                SkeletonData(
                    skeletonData = userData.skeletonDataCsv,
                    frameCount = userData.frameCount,
                    fps = userData.fps,
                    resolution = userData.resolution,
                ),
            )

        val saved = analysisResultRepository.saveAll(buildResults(userVideo, response))

        userVideo.status = VIDEO_STATUS_COMPLETED
        userVideoRepository.save(userVideo)
        return saved
    }

    /**
     * 분석 실패를 기록한다.
     *
     * REQUIRES_NEW를 쓰는 이유: 이 메서드는 본 작업이 실패한 뒤에 호출된다.
     * 진행 중인 트랜잭션에 참여하면 그 트랜잭션이 롤백될 때 FAILED 표시까지 함께 사라져,
     * 영상이 영원히 PENDING에 머무르게 된다.
     *
     * 상태 갱신 자체가 실패해도 예외를 밖으로 내보내지 않는다.
     * 원래 실패 원인을 덮어써 진단을 어렵게 만들기 때문이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markFailed(videoId: Long) {
        try {
            userVideoRepository.findById(videoId).ifPresent { video ->
                video.status = VIDEO_STATUS_FAILED
                userVideoRepository.save(video)
            }
        } catch (e: Exception) {
            log.error("영상 상태를 FAILED로 기록하지 못했습니다. videoId={}", videoId, e)
        }
    }
}
