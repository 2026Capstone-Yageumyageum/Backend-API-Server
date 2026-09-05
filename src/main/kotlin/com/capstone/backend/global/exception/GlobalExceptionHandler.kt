package com.capstone.backend.global.exception

import jakarta.persistence.EntityNotFoundException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.multipart.support.MissingServletRequestPartException
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * 모든 컨트롤러 예외를 하나의 [ErrorResponse] 형식으로 변환한다.
 *
 * 로그 정책:
 *  - 4xx(클라이언트 잘못)는 warn 레벨에 한 줄만. 스택트레이스를 남기지 않는다.
 *    잘못된 요청은 서버 버그가 아니므로, 스택을 찍으면 정작 중요한 로그가 묻힌다.
 *  - 5xx(서버/외부 장애)는 error 레벨에 스택트레이스까지. 원인 추적이 필요하다.
 *
 * 주의: 이 핸들러는 DispatcherServlet 안에서 발생한 예외만 잡는다.
 * 인증 실패처럼 Security 필터 단계에서 발생하는 예외는 여기 오지 않으므로,
 * SecurityConfig의 AuthenticationEntryPoint / AccessDeniedHandler에서 같은 형식으로 응답해야 한다.
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── 우리가 의도적으로 던진 예외 ────────────────────────────────────────────

    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        ex: BusinessException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val errorCode = ex.errorCode
        if (errorCode.status.is5xxServerError) {
            log.error("[{}] {} - {}", errorCode.name, request.requestURI, ex.message, ex)
        } else {
            log.warn("[{}] {} - {}", errorCode.name, request.requestURI, ex.message)
        }
        return respond(errorCode, ErrorResponse.of(errorCode, request.requestURI, ex.message))
    }

    // ── 입력값 검증 ───────────────────────────────────────────────────────────

    /** @Valid 로 검증한 @RequestBody DTO가 규칙을 어겼을 때. 어느 필드가 문제인지 함께 내려준다. */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors =
            ex.bindingResult.fieldErrors.map {
                ErrorResponse.FieldError(
                    field = it.field,
                    message = it.defaultMessage ?: "올바르지 않은 값입니다.",
                )
            }
        log.warn("[{}] {} - {}", ErrorCode.INVALID_INPUT.name, request.requestURI, fieldErrors)
        return respond(
            ErrorCode.INVALID_INPUT,
            ErrorResponse.of(ErrorCode.INVALID_INPUT, request.requestURI, fieldErrors),
        )
    }

    /** @Validated 를 붙인 파라미터(경로변수·쿼리스트링) 검증 실패. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        ex: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        val fieldErrors =
            ex.constraintViolations.map {
                ErrorResponse.FieldError(
                    field = it.propertyPath.toString().substringAfterLast('.'),
                    message = it.message,
                )
            }
        log.warn("[{}] {} - {}", ErrorCode.INVALID_INPUT.name, request.requestURI, fieldErrors)
        return respond(
            ErrorCode.INVALID_INPUT,
            ErrorResponse.of(ErrorCode.INVALID_INPUT, request.requestURI, fieldErrors),
        )
    }

    // ── 잘못된 형태의 요청 (Spring MVC 표준 예외) ─────────────────────────────

    /** 예: /api/analysis/abc/result — Long 자리에 숫자가 아닌 값이 들어온 경우. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(
        ex: MethodArgumentTypeMismatchException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = badRequest(ErrorCode.INVALID_INPUT, "${ex.name} 값의 형식이 올바르지 않습니다.", request)

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(
        ex: MissingServletRequestParameterException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = badRequest(ErrorCode.MISSING_PARAMETER, "필수 요청 값 ${ex.parameterName}이(가) 누락되었습니다.", request)

    /** 예: multipart 요청에서 file 파트가 빠졌을 때. */
    @ExceptionHandler(MissingServletRequestPartException::class)
    fun handleMissingPart(
        ex: MissingServletRequestPartException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> = badRequest(ErrorCode.MISSING_PARAMETER, "필수 파일 ${ex.requestPartName}이(가) 누락되었습니다.", request)

    /** 본문 JSON이 깨졌거나 타입이 맞지 않을 때. 파싱 실패 상세는 내부 구조를 드러내므로 노출하지 않는다. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleNotReadable(
        ex: HttpMessageNotReadableException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} - {}", ErrorCode.INVALID_JSON_FORMAT.name, request.requestURI, ex.message)
        return respond(
            ErrorCode.INVALID_JSON_FORMAT,
            ErrorResponse.of(ErrorCode.INVALID_JSON_FORMAT, request.requestURI),
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        ex: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} {}", ErrorCode.METHOD_NOT_ALLOWED.name, ex.method, request.requestURI)
        return respond(
            ErrorCode.METHOD_NOT_ALLOWED,
            ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED, request.requestURI),
        )
    }

    /** application.yaml의 max-file-size(500MB)를 넘긴 업로드. 500이 아니라 413이 맞다. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleMaxUploadSize(
        ex: MaxUploadSizeExceededException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} - {}", ErrorCode.FILE_TOO_LARGE.name, request.requestURI, ex.message)
        return respond(
            ErrorCode.FILE_TOO_LARGE,
            ErrorResponse.of(ErrorCode.FILE_TOO_LARGE, request.requestURI),
        )
    }

    // ── 인가 ──────────────────────────────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(
        ex: AccessDeniedException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} - {}", ErrorCode.ACCESS_DENIED.name, request.requestURI, ex.message)
        return respond(
            ErrorCode.ACCESS_DENIED,
            ErrorResponse.of(ErrorCode.ACCESS_DENIED, request.requestURI),
        )
    }

    // ── 외부 분석 서버(Python) ────────────────────────────────────────────────

    /** 분석 서버가 4xx/5xx로 응답. 응답 본문은 로그에만 남기고 클라이언트에는 노출하지 않는다. */
    @ExceptionHandler(WebClientResponseException::class)
    fun handleWebClientResponse(
        ex: WebClientResponseException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error(
            "[{}] {} - 분석 서버 응답 {}: {}",
            ErrorCode.ANALYSIS_FAILED.name,
            request.requestURI,
            ex.statusCode,
            ex.responseBodyAsString,
            ex,
        )
        return respond(
            ErrorCode.ANALYSIS_FAILED,
            ErrorResponse.of(ErrorCode.ANALYSIS_FAILED, request.requestURI),
        )
    }

    /** 분석 서버에 연결 자체가 실패(미기동·네트워크 단절·타임아웃). */
    @ExceptionHandler(WebClientRequestException::class)
    fun handleWebClientRequest(
        ex: WebClientRequestException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("[{}] {} - 분석 서버 연결 실패", ErrorCode.ANALYSIS_SERVER_UNAVAILABLE.name, request.requestURI, ex)
        return respond(
            ErrorCode.ANALYSIS_SERVER_UNAVAILABLE,
            ErrorResponse.of(ErrorCode.ANALYSIS_SERVER_UNAVAILABLE, request.requestURI),
        )
    }

    // ── 데이터 계층 ───────────────────────────────────────────────────────────

    /** unique 제약 위반 등. 예외 메시지에 SQL과 테이블·컬럼명이 들어 있으므로 절대 노출하지 않는다. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(
        ex: DataIntegrityViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("[{}] {} - 데이터 제약 위반", ErrorCode.INVALID_INPUT.name, request.requestURI, ex)
        return respond(
            ErrorCode.INVALID_INPUT,
            ErrorResponse.of(ErrorCode.INVALID_INPUT, request.requestURI, "요청을 처리할 수 없습니다. 입력값을 확인해 주세요."),
        )
    }

    /**
     * JPA가 던지는 "엔티티 없음". 우리 서비스 코드는 BusinessException을 쓰므로 여기 오지 않지만,
     * 지연 로딩 프록시를 초기화하는 시점에 대상 행이 이미 사라졌다면 JPA가 직접 던진다.
     *
     * 예외 메시지에 엔티티 클래스명과 식별자가 담기므로(예: "Unable to find ...UserVideo with id 5")
     * 응답에는 싣지 않고 로그에만 남긴다.
     */
    @ExceptionHandler(EntityNotFoundException::class)
    fun handleEntityNotFound(
        ex: EntityNotFoundException,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} - {}", ErrorCode.RESOURCE_NOT_FOUND.name, request.requestURI, ex.message)
        return respond(
            ErrorCode.RESOURCE_NOT_FOUND,
            ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND, request.requestURI),
        )
    }

    // ── 최후의 방어선 ─────────────────────────────────────────────────────────

    /**
     * 위에서 걸리지 않은 모든 예외.
     *
     * ex.message를 응답에 넣지 않는 것이 핵심이다.
     * NPE의 상세 메시지나 DB 드라이버 예외에는 내부 구조가 그대로 담겨 있어,
     * 그대로 내려보내면 정보 유출이 된다. 원인은 로그에만 남긴다.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.error("[{}] {} - 처리되지 않은 예외", ErrorCode.INTERNAL_ERROR.name, request.requestURI, ex)
        return respond(
            ErrorCode.INTERNAL_ERROR,
            ErrorResponse.of(ErrorCode.INTERNAL_ERROR, request.requestURI),
        )
    }

    // ── 내부 헬퍼 ─────────────────────────────────────────────────────────────

    private fun respond(
        errorCode: ErrorCode,
        body: ErrorResponse,
    ): ResponseEntity<ErrorResponse> = ResponseEntity.status(errorCode.status).body(body)

    private fun badRequest(
        errorCode: ErrorCode,
        message: String,
        request: HttpServletRequest,
    ): ResponseEntity<ErrorResponse> {
        log.warn("[{}] {} - {}", errorCode.name, request.requestURI, message)
        return respond(errorCode, ErrorResponse.of(errorCode, request.requestURI, message))
    }
}
