package com.capstone.backend.domain.analysis.entity

import com.capstone.backend.domain.video.entity.UserVideo
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "analysis_result")
class AnalysisResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "similarity_score", nullable = false)
    val similarityScore: Double,
    @Column(name = "feedback_text", columnDefinition = "TEXT")
    val feedbackText: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_video_id", nullable = false)
    val userVideo: UserVideo,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_id", nullable = false)
    val referenceModel: ReferenceModel,
)
