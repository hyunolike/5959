package com.ogu.ai.infrastructure

import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.EmotionAnalyzer
import com.ogu.shared.config.AiProperties
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import io.github.resilience4j.timelimiter.TimeLimiterConfig
import io.github.resilience4j.timelimiter.TimeLimiterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** 키가 비어 있으면(prod 밖) 글 본문을 외부 기준 URL로 보내지 않는다. */
class SpringAiAnalyzerConfigTest {
    private val requests = AtomicInteger()
    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun `키가 비어 있으면 HTTP 요청 없이 바로 UPSTREAM_ERROR로 실패하는 분석기를 쓴다`() {
        val analyzer = analyzerWithKey("")

        assertThat(analyzer).isInstanceOf(DisabledEmotionAnalyzer::class.java)
        assertThatThrownBy { analyzer.analyze(1L, "외부로 나가면 안 되는 본문") }
            .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
            }
        assertThat(requests.get()).isZero()
    }

    @Test
    fun `키가 있으면 설정한 기준 URL로 호출하는 실제 분석기를 쓴다`() {
        val analyzer = analyzerWithKey("real-key")

        assertThat(analyzer).isInstanceOf(SpringAiEmotionAnalyzer::class.java)
        assertThatThrownBy { analyzer.analyze(1L, "본문") }
            .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
            }
        assertThat(requests.get()).isEqualTo(1)
        (analyzer as SpringAiEmotionAnalyzer).close()
    }

    // TimeLimiterRegistry.ofDefaults()의 타임아웃은 1초다. 새 OpenAI 클라이언트의 첫 호출은 부하가 크면 1초를 넘겨
    // 스텁에 닿기 전에 TIMEOUT으로 끝날 수 있었다. 운영(application.yml)처럼 ogu.ai.timeout을 그대로 쓴다.
    private fun analyzerWithKey(apiKey: String): EmotionAnalyzer {
        val baseUrl = URI.create("http://127.0.0.1:${server.address.port}/v1")
        val properties = AiProperties(baseUrl = baseUrl, apiKey = apiKey)
        return SpringAiAnalyzerConfig().springAiEmotionAnalyzer(
            properties,
            CircuitBreakerRegistry.ofDefaults(),
            TimeLimiterRegistry.of(TimeLimiterConfig.custom().timeoutDuration(properties.timeout).build()),
        )
    }
}
