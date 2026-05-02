package com.capstone.backend.domain.video.repository

import com.capstone.backend.domain.user.entity.User
import com.capstone.backend.domain.video.entity.UserVideo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserVideoRepository : JpaRepository<UserVideo, Long> {
    fun findAllByUserOrderByUploadedAtDesc(user: User): List<UserVideo>
}
