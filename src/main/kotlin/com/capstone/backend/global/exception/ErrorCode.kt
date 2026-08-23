package com.capstone.backend.global.exception

import org.springframework.http.HttpStatus

/**
 * 애플리케이션이 클라이언트에게 돌려주는 모든 에러의 단일 출처.
 *
 * (식별 코드, HTTP 상태, 기본 메시지)를 한 곳에 묶어 두는 이유는,
 * 예외를 던지는 쪽이 상태 코드를 신경 쓰지 않게 하기 위해서다.
 * 서비스는 `throw BusinessException(ErrorCode.USER_NOT_FOUND)` 만 하면 되고,
 * 상태 코드 결정은 GlobalExceptionHandler 한 곳에서 일어난다.
 *
 * enum 이름이 그대로 응답의 `code` 값이 되므로,
 * 프론트엔드는 메시지 문자열이 아니라 이 코드로 분기한다.
 */
enum class ErrorCode(
    val status: HttpStatus,
    val message: String,
) {
    // ── 인증 / 인가 ────────────────────────────────────────────────────────────
    LOGIN_REQUIRED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 토큰입니다."),

    // 만료와 위조를 구분해야 클라이언트가 "토큰 갱신"과 "재로그인"을 나눠 처리할 수 있다.
    EXPIRED_TOKEN(HttpStatus.UNAUTHORIZED, "토큰이 만료되었습니다. 토큰을 갱신해 주세요."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "이미 사용되었거나 만료된 리프레시 토큰입니다. 다시 로그인해 주세요."),
    INVALID_GOOGLE_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 구글 토큰입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    NOT_VIDEO_OWNER(HttpStatus.FORBIDDEN, "본인의 영상에만 접근할 수 있습니다."),

    // ── 사용자 ────────────────────────────────────────────────────────────────
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),

    // ── 영상 / 분석 ───────────────────────────────────────────────────────────
    VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다."),
    BEST_PITCH_VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "비교할 최고의 1구 영상을 찾을 수 없습니다."),
    REFERENCE_MODEL_NOT_FOUND(HttpStatus.NOT_FOUND, "레퍼런스 모델을 찾을 수 없습니다."),
    SKELETON_DATA_NOT_READY(HttpStatus.CONFLICT, "최고의 1구 영상의 골격 데이터가 없습니다. 먼저 분석을 완료해 주세요."),

    // 분석 서버(Python)는 외부 시스템이므로 5xx 중에서도 502(Bad Gateway)가 의미상 정확하다.
    // 500과 구분해야 "우리 서버 버그"와 "분석 서버 장애"를 로그·모니터링에서 나눌 수 있다.
    ANALYSIS_SERVER_UNAVAILABLE(HttpStatus.BAD_GATEWAY, "분석 서버와 통신하지 못했습니다. 잠시 후 다시 시도해 주세요."),
    ANALYSIS_FAILED(HttpStatus.BAD_GATEWAY, "분석 서버가 분석에 실패했습니다."),
    INVALID_ANALYSIS_RESPONSE(HttpStatus.BAD_GATEWAY, "분석 서버 응답 형식이 올바르지 않습니다."),

    // ── 요청 / 입력 ───────────────────────────────────────────────────────────
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
    MISSING_PARAMETER(HttpStatus.BAD_REQUEST, "필수 요청 값이 누락되었습니다."),
    INVALID_FILE(HttpStatus.BAD_REQUEST, "업로드된 파일이 올바르지 않습니다."),
    INVALID_JSON_FORMAT(HttpStatus.BAD_REQUEST, "JSON 형식이 올바르지 않습니다."),
    FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "업로드 가능한 파일 크기를 초과했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다."),

    // ── 공통 ──────────────────────────────────────────────────────────────────
    // 어떤 종류인지 특정할 수 없는 "데이터 없음". JPA 프록시 초기화 실패 등에 쓴다.
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청하신 데이터를 찾을 수 없습니다."),
    FILE_PROCESSING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "파일 처리 중 오류가 발생했습니다."),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
}
