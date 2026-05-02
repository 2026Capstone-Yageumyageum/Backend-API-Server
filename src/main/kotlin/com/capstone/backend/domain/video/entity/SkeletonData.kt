package com.capstone.backend.domain.video.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "skeleton_data")
class SkeletonData(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "skeleton_data", columnDefinition = "jsonb", nullable = false)
    val SkeletonData: String,
    @Column(name = "frame_count", nullable = false)
    val frameCount: Int,
    @Column(nullable = false)
    val fps: Int,
    @Column(length = 20)
    val resolution: String?,
)
