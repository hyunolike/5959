package com.ogu.ai.infrastructure

import com.ogu.ai.EmbeddingFailed
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.shared.config.AiProperties
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/** T005: OpenAI 호환 `/embeddings` 호출(007 research R2). 가짜 HTTP 서버로 요청과 실패의 분류를 본다. */
@ExtendWith(OutputCaptureExtension::class)
class HttpEmbedderTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<Pair<String, String>>()
    private val responder = AtomicReference<() -> Pair<Int, String>> { 200 to embedding(DIMENSIONS) }
    private lateinit var server: HttpServer

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/embeddings") { exchange ->
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            requests += exchange.requestHeaders.getFirst("Authorization") to body
            val (status, response) = responder.get()()
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `본문을 passage로 보내고 모델과 값을 돌려준다`() {
        val result = embedder().embed("POST:1", CONTENT)

        assertThat(result.model).isEqualTo(MODEL)
        assertThat(result.values).hasSize(DIMENSIONS)
        assertThat(result.values[0]).isEqualTo(0.25f)
        val (authorization, raw) = requests.single()
        assertThat(authorization).isEqualTo("Bearer test-key")
        val body = jsonMapper.readTree(raw)
        assertThat(body.get("model").asString()).isEqualTo(MODEL)
        assertThat(body.get("input").single().asString()).isEqualTo(CONTENT)
        // 글끼리 견주므로 언제나 문서로 만든다
        assertThat(body.get("input_type").asString()).isEqualTo("passage")
        assertThat(body.get("encoding_format").asString()).isEqualTo("float")
    }

    @Test
    fun `차원이 다르거나 형식이 틀린 응답은 INVALID_RESPONSE다`() {
        val bodies =
            listOf(
                embedding(DIMENSIONS - 1),
                """{"data":[]}""",
                "JSON이 아님",
                """{"data":[{"embedding":"x"}]}""",
            )
        bodies.forEach { body ->
            responder.set { 200 to body }
            assertFails(Kind.INVALID_RESPONSE)
        }
    }

    @Test
    fun `공급자가 오류를 주면 UPSTREAM_ERROR이고 로그에 본문과 응답이 없다`(output: CapturedOutput) {
        responder.set { 410 to """{"detail":"$PROVIDER_DETAIL"}""" }

        assertFails(Kind.UPSTREAM_ERROR)

        assertThat(output.all).contains("임베딩 호출 실패").contains("POST:1")
        assertThat(output.all).doesNotContain(CONTENT, PROVIDER_DETAIL, "test-key")
    }

    @Test
    fun `응답이 늦으면 TIMEOUT이다`() {
        responder.set {
            Thread.sleep(SLOW_MILLIS)
            200 to embedding(DIMENSIONS)
        }

        assertThatThrownBy { embedder(timeout = Duration.ofMillis(200)).embed("POST:1", CONTENT) }
            .isInstanceOfSatisfying(EmbeddingFailed::class.java) { assertThat(it.kind).isEqualTo(Kind.TIMEOUT) }
    }

    @Test
    fun `연달아 실패하면 서킷이 열려 공급자를 부르지 않는다`() {
        responder.set { 500 to "{}" }
        val breaker =
            CircuitBreaker.of(
                "embedderTest",
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50f)
                    .build(),
            )
        val embedder = embedder(breaker = breaker)
        repeat(2) { assertThatThrownBy { embedder.embed("POST:1", CONTENT) }.isInstanceOf(EmbeddingFailed::class.java) }

        assertThatThrownBy { embedder.embed("POST:1", CONTENT) }
            .isInstanceOfSatisfying(EmbeddingFailed::class.java) { assertThat(it.kind).isEqualTo(Kind.CIRCUIT_OPEN) }
        assertThat(requests).hasSize(2)
    }

    private fun assertFails(kind: Kind) {
        assertThatThrownBy { embedder().embed("POST:1", CONTENT) }
            .isInstanceOfSatisfying(EmbeddingFailed::class.java) { assertThat(it.kind).isEqualTo(kind) }
    }

    private fun embedder(
        timeout: Duration = Duration.ofSeconds(5),
        breaker: CircuitBreaker = CircuitBreaker.ofDefaults("embedderTest"),
    ): HttpEmbedder {
        val properties =
            AiProperties(
                baseUrl = URI.create("http://127.0.0.1:${server.address.port}/v1"),
                apiKey = "test-key",
                embeddingModel = MODEL,
                embeddingDimensions = DIMENSIONS,
                timeout = timeout,
            )
        return HttpEmbedder(properties, breaker)
    }

    private fun embedding(dimensions: Int): String {
        val values = (1..dimensions).joinToString(",") { "0.25" }
        return """{"object":"list","data":[{"index":0,"embedding":[$values]}],"model":"$MODEL"}"""
    }

    private companion object {
        const val MODEL = "test/embed-model"
        const val DIMENSIONS = 8
        const val CONTENT = "오구임베딩요청본문"
        const val PROVIDER_DETAIL = "공급자가 준 자세한 오류"
        const val SLOW_MILLIS = 1_000L
    }
}
