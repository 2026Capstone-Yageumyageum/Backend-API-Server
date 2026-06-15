package com.capstone.backend.domain.video.repository

import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.video.entity.UserVideo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserVideoRepository : JpaRepository<UserVideo, Long> {
    fun findAllByUserOrderByUploadedAtDesc(user: User): List<UserVideo>

    // 특정 구종의 "최고의 1구"로 등록된 영상(구종당 1개 유지). 없으면 null.
    fun findFirstByUserAndPitchTypeAndIsBestPitchTrue(
        user: User,
        pitchType: String,
    ): UserVideo?

    // 사용자가 등록한 모든 "최고의 1구"(구종별 카드 목록용).
    fun findAllByUserAndIsBestPitchTrue(user: User): List<UserVideo>
}
