package com.capstone.backend.global.security

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 내부 API 공유 키가 설정된 배포(코드스페이스)에서의 접근 통제.
 *
 * 코드스페이스는 공개 포트로 들어온 외부 요청을 내부 포워딩 프로그램이 localhost로 넘기므로,
 * 외부 요청도 전부 127.0.0.1에서 온 것처럼 보인다. IP만으로는 막을 수 없어서 키를 함께 본다.
 * 키가 없는 기본 설정의 동작(IP만 검사)은 SecurityAccessControlTest가 검증한다.
 */
@SpringBootTest(properties = ["app.internal-api.key=test-internal-api-key"])
@AutoConfigureMockMvc
class InternalApiKeyTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    @DisplayName("키가 설정되면 loopback이어도 키 없는 내부 API 요청은 403이다")
    fun missingKey_isForbiddenEvenFromLoopback() {
        mockMvc
            .perform(referenceModels().fromIp(LOOPBACK))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
    }

    @Test
    @DisplayName("틀린 키는 403이다")
    fun wrongKey_isForbidden() {
        mockMvc
            .perform(referenceModels().fromIp(LOOPBACK).header(HEADER, "wrong-key"))
            .andExpect(status().isForbidden())
    }

    @Test
    @DisplayName("맞는 키와 허용 IP면 통과한다")
    fun rightKeyFromLoopback_isAllowed() {
        mockMvc
            .perform(referenceModels().fromIp(LOOPBACK).header(HEADER, TEST_KEY))
            .andExpect(status().isOk())
    }

    @Test
    @DisplayName("키가 맞아도 허용 대역 밖 IP면 403이다 — 키는 IP 검사를 대체하지 않고 더해진다")
    fun rightKeyFromExternalIp_isForbidden() {
        mockMvc
            .perform(referenceModels().fromIp(EXTERNAL).header(HEADER, TEST_KEY))
            .andExpect(status().isForbidden())
    }

    @Test
    @DisplayName("레퍼런스 전체 삭제도 키 없이는 loopback에서 호출할 수 없다")
    fun clearWithoutKey_isForbiddenFromLoopback() {
        mockMvc
            .perform(delete("/api/internal/analysis/reference-models/clear").fromIp(LOOPBACK))
            .andExpect(status().isForbidden())
    }

    private fun referenceModels(): MockHttpServletRequestBuilder = get("/api/internal/analysis/reference-models")

    private fun MockHttpServletRequestBuilder.fromIp(ip: String): MockHttpServletRequestBuilder =
        this.with { request ->
            request.remoteAddr = ip
            request
        }
}

// @SpringBootTest의 properties 값과 같아야 한다.
private const val TEST_KEY = "test-internal-api-key"
private const val HEADER = "X-Internal-Api-Key"
private const val LOOPBACK = "127.0.0.1"
private const val EXTERNAL = "203.0.113.5" // 문서화용 예약 대역(RFC 5737)
