package com.capstone.backend.domain.user.repository

import com.capstone.backend.domain.user.entity.RefreshToken
import org.springframework.data.repository.CrudRepository

interface RefreshTokenRepository : CrudRepository<RefreshToken, Long>
