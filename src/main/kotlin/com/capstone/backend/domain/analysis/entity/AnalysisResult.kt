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
    // 파이썬이 보낸 player 원본(phaseScores·release·feedback)을 JSON 문자열로 보관 (리포트 상세용)
    @Column(name = "detail_json", columnDefinition = "TEXT")
    val detailJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 비교 종류: "PRO"(프로 선수 비교) | "BEST_PITCH"(내 최고의 1구 비교). 기본은 기존 동작인 PRO.
    // 기존 행에도 안전하게 추가되도록 DB 기본값 'PRO' 지정(ddl-auto=update).
    @Column(name = "comparison_type", nullable = false, columnDefinition = "varchar(20) default 'PRO'")
    val comparisonType: String = "PRO",
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_video_id", nullable = false)
    val userVideo: UserVideo,
    // PRO 비교일 때만 채워진다. BEST_PITCH 비교에서는 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reference_id")
    val referenceModel: ReferenceModel? = null,
    // BEST_PITCH 비교일 때만 채워진다(비교 대상이 된 "최고의 1구" 영상). PRO 비교에서는 null.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "best_pitch_video_id")
    val bestPitchVideo: UserVideo? = null,
)
