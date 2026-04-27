package com.capstone.backend.domain.user.entity

import jakarta.persistence.Id
import org.springframework.data.redis.core.RedisHash
import org.springframework.data.redis.core.TimeToLive

@RedisHash("refreshToken")
class RefreshToken (
    @Id
    val refreshToken: String,
    val userId: Long,
    @TimeToLive
    val ttl: Long
)