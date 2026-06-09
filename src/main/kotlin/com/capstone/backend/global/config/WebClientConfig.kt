package com.capstone.backend.global.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

private const val PYTHON_WEBCLIENT_MAX_IN_MEMORY_BYTES = 50 * 1024 * 1024

@Configuration
class WebClientConfig {
    @Bean
    fun pythonWebClient(): WebClient =
        WebClient
            .builder()
            .baseUrl("http://127.0.0.1:5020")
            .codecs { configurer ->
                configurer.defaultCodecs().maxInMemorySize(PYTHON_WEBCLIENT_MAX_IN_MEMORY_BYTES)
            }.build()
}
