package com.capstone.backend.domain.video.entity

import com.capstone.backend.domain.user.entity.User
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "user_video")
class UserVideo(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "video_url", nullable = false, length = 512)
    val videoUrl: String,
    @Column(nullable = false, length = 20)
    var status: String = "PENDING",
    // 사용자가 카메라에서 선택한 구종 (직구/슬라이더/커브/체인지업)
    // 기존 행에도 추가될 수 있도록 nullable. 값이 없으면 코드에서 "직구"로 간주한다.
    @Column(name = "pitch_type", length = 20)
    var pitchType: String? = null,
    @Column(name = "uploaded_at", nullable = false, updatable = false)
    val uploadedAt: LocalDateTime = LocalDateTime.now(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "skeleton_data_id")
    var skeletonData: SkeletonData? = null,
)
