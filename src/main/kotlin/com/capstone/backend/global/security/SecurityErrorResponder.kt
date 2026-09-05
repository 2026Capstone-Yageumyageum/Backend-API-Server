package com.capstone.backend.global.security

import com.capstone.backend.global.exception.ErrorCode
import com.capstone.backend.global.exception.ErrorResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Security 필터 단계에서 발생한 실패를 GlobalExceptionHandler와 같은 형식으로 응답한다.
 *
 * 왜 별도 클래스가 필요한가:
 * @RestControllerAdvice는 DispatcherServlet 안쪽 예외만 잡는다.
 * 인증 실패는 그보다 앞단인 필터 체인에서 결정되므로 핸들러에 도달하지 않고,
 * 그대로 두면 Spring Security 기본 응답(HTML 또는 {"status":401,"error":"Unauthorized"})이
 * 나가 우리 ErrorResponse 형식과 뒤섞인다. 클라이언트가 두 가지 형식을 파싱해야 하는 상황을 막는다.
 */
private const val TOKEN_ERROR_ATTRIBUTE = "com.capstone.backend.TOKEN_ERROR"

/** 필터가 판별한 토큰 오류를 EntryPoint에 넘기기 위한 통로. 요청 하나의 수명 동안만 유지된다. */
fun HttpServletRequest.setTokenError(errorCode: ErrorCode) {
    setAttribute(TOKEN_ERROR_ATTRIBUTE, errorCode)
}

fun HttpServletRequest.getTokenError(): ErrorCode? = getAttribute(TOKEN_ERROR_ATTRIBUTE) as? ErrorCode

private fun writeError(
    request: HttpServletRequest,
    response: HttpServletResponse,
    errorCode: ErrorCode,
    objectMapper: ObjectMapper,
) {
    response.status = errorCode.status.value()
    response.contentType = MediaType.APPLICATION_JSON_VALUE
    response.characterEncoding = Charsets.UTF_8.name()
    response.writer.write(
        objectMapper.writeValueAsString(ErrorResponse.of(errorCode, request.requestURI)),
    )
}

/**
 * 인증되지 않은 요청이 보호된 자원에 접근했을 때(401).
 *
 * 토큰을 아예 보내지 않았는지, 만료됐는지, 위조됐는지에 따라 코드를 달리 내려준다.
 * 그래야 앱이 "토큰 갱신"과 "재로그인"을 구분해 처리할 수 있다.
 */
@Component
class JwtAuthenticationEntryPoint(
    private val objectMapper: ObjectMapper,
) : AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        val errorCode = request.getTokenError() ?: ErrorCode.LOGIN_REQUIRED
        log.warn("[{}] {} - 인증 실패", errorCode.name, request.requestURI)
        writeError(request, response, errorCode, objectMapper)
    }
}

/**
 * 접근 거부를 항상 403으로 응답한다.
 *
 * AccessDeniedHandler와 AuthenticationEntryPoint를 모두 구현하는 이유:
 * Spring Security는 익명 사용자가 접근 거부를 당하면 AccessDeniedHandler가 아니라
 * AuthenticationEntryPoint를 호출해 401을 내보낸다("일단 로그인하라"는 뜻).
 * 그러나 IP 기반 제한처럼 인증과 무관한 거부는 토큰을 가져와도 결과가 같으므로,
 * 401은 클라이언트에게 잘못된 신호(재시도하면 될 것 같은)를 준다.
 * 내부 전용 체인에서는 이 클래스를 EntryPoint로도 등록해 항상 403이 나가게 한다.
 */
@Component
class JsonAccessDeniedHandler(
    private val objectMapper: ObjectMapper,
) : AccessDeniedHandler,
    AuthenticationEntryPoint {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) = deny(request, response)

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) = deny(request, response)

    private fun deny(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        log.warn("[{}] {} - 접근 거부 (요청 IP: {})", ErrorCode.ACCESS_DENIED.name, request.requestURI, request.remoteAddr)
        writeError(request, response, ErrorCode.ACCESS_DENIED, objectMapper)
    }
}
