package com.capstone.backend_server.domain.user.repository

import com.capstone.backend_server.domain.user.entity.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    fun existsByNickname(nickname: String): Boolean
}
