package com.capstone.backend.global.filter

import com.capstone.backend.global.exception.ErrorCode
import com.capstone.backend.global.security.setTokenError
import com.capstone.backend.global.util.JwtUtil
import com.capstone.backend.global.util.TokenStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

private const val BEARER_PREFIX = "Bearer "

@Component
class JwtAuthenticationFilter(
    private val jwtUtil: JwtUtil,
) : OncePerRequestFilter() {
    override fun shouldNotFilterAsyncDispatch(): Boolean = false

    /**
     * 토큰이 유효하면 SecurityContext에 인증 정보를 채우고, 아니면 오류 종류만 기록한 뒤 통과시킨다.
     *
     * 여기서 직접 401을 쓰지 않는 이유:
     * 이 필터는 "토큰이 있으면 해석한다"까지만 책임진다. 해당 요청이 인증을 요구하는지는
     * SecurityConfig의 경로 규칙이 정하므로, 실제 거절은 그 판단이 끝난 뒤
     * JwtAuthenticationEntryPoint에서 이뤄진다. 공개 API에 만료된 토큰을 붙여 보내도
     * 정상 동작해야 하기 때문이다.
     */
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = request.getHeader("Authorization")?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)

        if (token != null) {
            when (jwtUtil.validateToken(token)) {
                TokenStatus.VALID -> authenticate(request, token)
                TokenStatus.EXPIRED -> request.setTokenError(ErrorCode.EXPIRED_TOKEN)
                TokenStatus.INVALID -> request.setTokenError(ErrorCode.INVALID_TOKEN)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun authenticate(
        request: HttpServletRequest,
        token: String,
    ) {
        // 서명은 유효하지만 subject가 숫자가 아닌 경우(조작된 토큰)를 걸러낸다.
        val userId = jwtUtil.getIdFromToken(token)
        if (userId == null) {
            request.setTokenError(ErrorCode.INVALID_TOKEN)
            return
        }
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, emptyList())
    }
}
