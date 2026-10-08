package com.ogu.notification.stream

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * 스트림 경로에만 CORS를 연다(research R3). 브라우저는 웹 도메인에서 API 도메인의 스트림에 바로 붙으므로 그 출처
 * (`ogu.sse.allowed-origins`, `OGU_SSE_ALLOWED_ORIGINS`)만 허용한다. 인증은 주소의 일회용 표로 하므로 자격 증명(쿠키)은
 * 쓰지 않는다. 목록이 비면 어떤 다른 출처도 허용하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
class StreamCorsConfig(
    @param:Value("\${ogu.sse.allowed-origins:}") private val allowedOrigins: List<String>,
) : WebMvcConfigurer {
    override fun addCorsMappings(registry: CorsRegistry) {
        registry
            .addMapping(STREAM_PATH)
            .allowedOrigins(*allowedOrigins.map(String::trim).filter(String::isNotEmpty).toTypedArray())
            .allowedMethods(HttpMethod.GET.name())
            .allowedHeaders(LAST_EVENT_ID)
            .allowCredentials(false)
    }

    companion object {
        const val STREAM_PATH = "/api/v1/notifications/stream"
        private const val LAST_EVENT_ID = "Last-Event-ID"
    }
}
