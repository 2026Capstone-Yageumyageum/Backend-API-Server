package com.capstone.backend.domain.user.service

import com.capstone.backend.domain.analysis.entity.AnalysisResult
import com.capstone.backend.domain.analysis.repository.AnalysisResultRepository
import com.capstone.backend.domain.analysis.service.COMPARISON_PRO
import com.capstone.backend.domain.analysis.service.DEFAULT_PITCH_TYPE
import com.capstone.backend.domain.analysis.service.VIDEO_STATUS_COMPLETED
import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.user.repository.UserRepository
import com.capstone.backend.domain.video.entity.UserVideo
import com.capstone.backend.domain.video.repository.UserVideoRepository
import jakarta.persistence.EntityManagerFactory
import org.assertj.core.api.Assertions.assertThat
import org.hibernate.SessionFactory
import org.hibernate.stat.Statistics
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.util.UUID

/**
 * 목록 조회가 N+1 쿼리를 내지 않는지 확인한다.
 *
 * Hibernate Statistics로 실제 실행된 쿼리 수를 센다.
 * "빨라졌다"는 느낌이 아니라 숫자로 고정해두어야, 나중에 누가 반복문 안에
 * 레포지토리 호출을 다시 넣었을 때 테스트가 잡아준다.
 */
@SpringBootTest
@TestPropertySource(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class UserServiceQueryCountTest {
    @Autowired
    private lateinit var userService: UserService

    @Autowired
    private lateinit var userRepository: UserRepository

    @Autowired
    private lateinit var userVideoRepository: UserVideoRepository

    @Autowired
    private lateinit var analysisResultRepository: AnalysisResultRepository

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    private fun statistics(): Statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics

    /** 완료 상태의 영상 [count]개와 각각의 분석 결과를 만든다. */
    private fun seedUserWithVideos(count: Int): Long {
        val user =
            userRepository.save(
                User(email = "nplus1_${UUID.randomUUID()}@test.com", nickname = "n+1-test"),
            )
        repeat(count) {
            val video =
                userVideoRepository.save(
                    UserVideo(
                        user = user,
                        videoUrl = "v$it.mp4",
                        status = VIDEO_STATUS_COMPLETED,
                        pitchType = DEFAULT_PITCH_TYPE,
                    ),
                )
            analysisResultRepository.save(
                AnalysisResult(
                    similarityScore = 80.0 + it,
                    feedbackText = "test",
                    comparisonType = COMPARISON_PRO,
                    userVideo = video,
                ),
            )
        }
        return user.id!!
    }

    @Test
    @DisplayName("내 분석 목록은 영상 개수와 무관하게 일정한 쿼리 수로 조회된다")
    fun getMyAnalyses_doesNotScaleWithVideoCount() {
        val fewUserId = seedUserWithVideos(2)
        val manyUserId = seedUserWithVideos(10)

        val stats = statistics()

        stats.clear()
        userService.getMyAnalyses(fewUserId)
        val queriesForFew = stats.prepareStatementCount

        stats.clear()
        userService.getMyAnalyses(manyUserId)
        val queriesForMany = stats.prepareStatementCount

        // 예전에는 영상마다 결과를 조회해 영상 수에 비례해 쿼리가 늘었다(2개 → 3회, 10개 → 11회).
        // 지금은 영상 목록 1회 + 결과 목록 1회로 끝나므로 개수가 5배 늘어도 쿼리 수는 그대로다.
        assertThat(queriesForMany)
            .withFailMessage(
                "영상 2개일 때 %d회, 10개일 때 %d회 — 영상 수에 따라 쿼리가 늘고 있습니다(N+1).",
                queriesForFew,
                queriesForMany,
            ).isEqualTo(queriesForFew)
    }

    @Test
    @DisplayName("통계 조회도 영상 개수에 따라 쿼리가 늘지 않는다")
    fun getStats_doesNotScaleWithVideoCount() {
        val fewUserId = seedUserWithVideos(2)
        val manyUserId = seedUserWithVideos(10)

        val stats = statistics()

        stats.clear()
        userService.getStats(fewUserId)
        val queriesForFew = stats.prepareStatementCount

        stats.clear()
        userService.getStats(manyUserId)
        val queriesForMany = stats.prepareStatementCount

        assertThat(queriesForMany).isEqualTo(queriesForFew)
    }
}
