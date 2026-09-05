package com.capstone.backend.domain.analysis.dto

// 컨트롤러가 돌려주는 응답 형식.
//
// 이전에는 전부 Map<String, Any>였다. 그러면 두 가지를 잃는다.
//  - 타입 안전성: 키 이름을 오타 내도 컴파일이 통과하고, 값 타입도 강제되지 않는다.
//  - API 문서: Swagger가 응답 스키마를 추론하지 못해 문서에 빈 객체로 표시된다.

/** 분석 요청 수락 응답. 실제 분석은 백그라운드에서 진행되고 앱은 videoId로 폴링한다. */
data class AnalysisStartResponse(
    val videoId: Long,
    val status: String,
    val message: String,
)

/** 최고의 1구 등록 결과. */
data class BestPitchRegisterResponse(
    val videoId: Long,
    val message: String,
)

/** 골격 데이터 조회 결과. 아직 분석 전이면 빈 문자열과 0이 담긴다. */
data class SkeletonDataResponse(
    val skeletonData: String,
    val frameCount: Int,
)

/** 레퍼런스 모델 단건 등록 결과. */
data class ReferenceModelSaveResponse(
    val referenceId: Long,
    val message: String,
)

/**
 * 레퍼런스 모델 일괄 등록 결과.
 *
 * skippedCount를 함께 돌려주는 이유: 골격 데이터가 없는 항목은 조용히 건너뛰는데,
 * 성공 수만 알려주면 "50건 올렸는데 30건만 저장된" 상황을 알아챌 수 없다.
 */
data class BulkUploadResponse(
    val savedCount: Int,
    val skippedCount: Int,
    val message: String,
)

/** 별다른 반환값이 없는 작업의 응답. */
data class MessageResponse(
    val message: String,
)
