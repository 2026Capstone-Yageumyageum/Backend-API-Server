package com.capstone.backend.domain.analysis.entity

import com.capstone.backend.domain.video.entity.SkeletonData
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToOne
import jakarta.persistence.Table

@Entity
@Table(name = "reference_model")
class ReferenceModel(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "pitcher_name", nullable = false, length = 50)
    val pitcherName: String,
    @Column(name = "pitch_type", nullable = false, length = 30)
    val pitchType: String,
    @Column(name = "source_url", length = 512)
    val sourceUrl: String?,
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skeleton_data_id", nullable = false)
    val skeletonData: SkeletonData,
)
