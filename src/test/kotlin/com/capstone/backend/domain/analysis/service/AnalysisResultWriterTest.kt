package com.capstone.backend.domain.analysis.service

import com.capstone.backend.domain.analysis.dto.AnalysisResponse
import com.capstone.backend.domain.analysis.dto.UserDataDto
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.SkeletonDataRepository
import com.capstone.backend.domain.video.repository.UserVideoRepository
import com.capstone.backend.global.exception.BusinessException
import com.capstone.backend.global.exception.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.util.UUID

/**
 * 분석 결과 저장의 트랜잭션 경계를 검증한다.
 *
 * 테스트 메서드에 @Transactional을 붙이지 않는 것이 중요하다.
 * 붙이면 테스트 자체가 하나의 트랜잭션이 되어, 검증하려는 "실패 시 롤백"이
 * 테스트 트랜잭션에 가려 보이지 않는다.
 */
@SpringBootTest
class AnalysisResultWriterTest {
    @Autowired
    private lateinit var writer: AnalysisResultWriter

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userVideoRepository: UserVideoRepository

    @Autowired
    private lateinit var skeletonDataRepository: SkeletonDataRepository

    @Autowired
    private lateinit var analysisResultRepository: AnalysisResultRepository

    private fun newVideo(): UserVideo {
        val user =
            userRepository.save(
                User(email = "writer_${UUID.randomUUID()}@test.com", nickname = "writer-test"),
            )
        return userVideoRepository.save(
            UserVideo(user = user, videoUrl = "test.mp4", pitchType = DEFAULT_PITCH_TYPE),
        )
    }

    private fun response() =
        AnalysisResponse(
            videoId = "1",
            status = "completed",
            userData =
                UserDataDto(
                    skeletonDataId = "s1",
                    skeletonDataCsv = "frame,x,y\n1,0.1,0.2",
                    frameCount = 100,
                    fps = 30.0,
                    resolution = "1920x1080",
                ),
            players = emptyList(),
        )

    @Test
    @DisplayName("정상 저장 시 스켈레톤이 연결되고 상태가 COMPLETED로 바뀐다")
    fun saveSuccess_persistsAndCompletes() {
        val video = newVideo()

        writer.saveSuccess(video.id!!, response()) { _, _ -> emptyList() }

        val reloaded = userVideoRepository.findById(video.id!!).get()
        assertThat(reloaded.status).isEqualTo(VIDEO_STATUS_COMPLETED)
        assertThat(reloaded.skeletonData).isNotNull
        assertThat(reloaded.skeletonData!!.frameCount).isEqualTo(100)
    }

    @Test
    @DisplayName("결과 매핑이 실패하면 스켈레톤 저장도 함께 롤백되어 고아 데이터가 남지 않는다")
    fun saveSuccess_rollsBackSkeletonOnFailure() {
        val video = newVideo()
        val skeletonCountBefore = skeletonDataRepository.count()

        assertThatThrownBy {
            writer.saveSuccess(video.id!!, response()) { _, _ ->
                // 분석 서버가 DB에 없는 프로 ID를 돌려준 상황을 재현한다.
                throw BusinessException(ErrorCode.REFERENCE_MODEL_NOT_FOUND)
            }
        }.isInstanceOf(BusinessException::class.java)

        // 예전 구현은 map 블록 안에서 트랜잭션 없이 저장해, 스켈레톤만 남고 결과는 없는
        // 고아 데이터가 생겼다. 이제는 하나의 트랜잭션이라 통째로 되돌아간다.
        assertThat(skeletonDataRepository.count()).isEqualTo(skeletonCountBefore)

        val reloaded = userVideoRepository.findById(video.id!!).get()
        assertThat(reloaded.status).isEqualTo(VIDEO_STATUS_PENDING)
        assertThat(reloaded.skeletonData).isNull()
    }

    @Test
    @DisplayName("markFailed는 영상 상태를 FAILED로 남긴다")
    fun markFailed_recordsFailure() {
        val video = newVideo()

        writer.markFailed(video.id!!)

        assertThat(userVideoRepository.findById(video.id!!).get().status).isEqualTo(VIDEO_STATUS_FAILED)
    }

    @Test
    @DisplayName("존재하지 않는 영상에 markFailed를 호출해도 예외를 밖으로 던지지 않는다")
    fun markFailed_isSafeForMissingVideo() {
        // 실패 처리 도중 다시 실패하면 원래 실패 원인이 덮여 진단이 어려워진다.
        writer.markFailed(999_999L)
    }

    @Test
    @DisplayName("없는 영상에 저장을 시도하면 VIDEO_NOT_FOUND를 던진다")
    fun saveSuccess_missingVideo() {
        assertThatThrownBy {
            writer.saveSuccess(999_999L, response()) { _, _ -> emptyList() }
        }.isInstanceOf(BusinessException::class.java)
            .hasFieldOrPropertyWithValue("errorCode", ErrorCode.VIDEO_NOT_FOUND)

        assertThat(analysisResultRepository.findByUserVideoId(999_999L)).isEmpty()
    }
}
