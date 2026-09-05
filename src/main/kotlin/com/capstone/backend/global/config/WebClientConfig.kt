package com.capstone.backend.global.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.time.Duration

/** 분석 응답에는 골격 CSV 전체가 담겨 기본 256KB 버퍼로는 부족하다. */
private const val PYTHON_WEBCLIENT_MAX_IN_MEMORY_BYTES = 50 * 1024 * 1024

@Configuration
class WebClientConfig {
    @Bean
    fun pythonWebClient(
        @Value("\${app.python-analysis.base-url}") baseUrl: String,
        @Value("\${app.python-analysis.connect-timeout-seconds}") connectTimeoutSeconds: Long,
        @Value("\${app.python-analysis.response-timeout-minutes}") responseTimeoutMinutes: Long,
    ): WebClient {
        // 타임아웃이 없으면 분석 서버가 응답하지 않을 때 요청이 영원히 매달려
        // 커넥션과 스레드가 고갈된다. 영상 분석은 원래 오래 걸리므로 값은 넉넉히 두되
        // 무한정은 아니게 반드시 명시한다.
        val httpClient =
            HttpClient
                .create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (connectTimeoutSeconds * 1000).toInt())
                .responseTimeout(Duration.ofMinutes(responseTimeoutMinutes))

        return WebClient
            .builder()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(httpClient))
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(PYTHON_WEBCLIENT_MAX_IN_MEMORY_BYTES)
            }.build()
    }
}
