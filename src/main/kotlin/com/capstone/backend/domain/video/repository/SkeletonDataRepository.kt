package com.capstone.backend.domain.video.repository

import com.capstone.backend.domain.video.entity.SkeletonData
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface SkeletonDataRepository : JpaRepository<SkeletonData, Long>
