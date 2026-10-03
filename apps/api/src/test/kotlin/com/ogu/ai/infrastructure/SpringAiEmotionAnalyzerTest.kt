package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.ClassifiedIntensity
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.shared.config.AiProperties
import com.sun.net.httpserver.HttpServer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.timelimiter.TimeLimiter
import io.github.resilience4j.timelimiter.TimeLimiterConfig
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * T018: OpenAI 호환 엔드포인트를 로컬 HTTP 스텁으로 대신해 감정 분석기를 검증한다(research R3).
 */
@ExtendWith(OutputCaptureExtension::class)
class SpringAiEmotionAnalyzerTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<String>()
    private val responder = AtomicReference<() -> Pair<Int, String>> { 200 to completion(VALID_JSON) }
    private val release = CountDownLatch(1)
    private lateinit var server: HttpServer
    private val analyzers = mutableListOf<SpringAiEmotionAnalyzer>()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v1/chat/completions") { exchange ->
            requests += exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val (status, body) = responder.get().invoke()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() {
        release.countDown()
        analyzers.forEach { it.close() }
        server.stop(0)
    }

    @Test
    fun `요청에 시스템 프롬프트, 본문, 모델, temperature, max_tokens가 들어간다`() {
        val analyzer = analyzer()
        val content = "내일 발표가 {너무} 걱정돼요"

        analyzer.analyze(POST_ID, content)

        assertThat(requests).hasSize(1)
        val body = jsonMapper.readTree(requests.single())
        assertThat(body.get("model").asString()).isEqualTo(MODEL)
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.1)
        assertThat(body.get("max_tokens").asInt()).isEqualTo(200)
        val messages = body.get("messages")
        assertThat(messages.get(0).get("role").asString()).isEqualTo("system")
        val systemPrompt = messages.get(0).get("content").asString()
        assertThat(systemPrompt).isEqualTo(PromptLoader.load("v1"))
        assertThat(systemPrompt).contains("ANXIETY", "LETHARGY", "LONELINESS", "SELF_DEPRECATION", "IRRITATION")
        assertThat(systemPrompt).contains("\"emotion\"", "\"intensity\"", "\"reason\"")
        assertThat(systemPrompt).doesNotContain("profanity", "욕설")
        // 본문은 템플릿으로 렌더링하지 않고 사용자 메시지로 그대로 보낸다(중괄호가 있어도 깨지지 않는다)
        assertThat(messages.get(1).get("role").asString()).isEqualTo("user")
        assertThat(messages.get(1).get("content").asString()).isEqualTo(content)
    }

    @Test
    fun `정상 JSON 응답을 감정, 강도, 근거로 파싱한다`() {
        responder.set { 200 to completion("""{"emotion":"SELF_DEPRECATION","intensity":"MEDIUM","reason":"자책"}""") }

        val result = analyzer().analyze(POST_ID, "나는 왜 이럴까")

        assertThat(result.emotion).isEqualTo(ClassifiedEmotion.SELF_DEPRECATION)
        assertThat(result.intensity).isEqualTo(ClassifiedIntensity.MEDIUM)
        assertThat(result.reason).isEqualTo("자책")
    }

    @Test
    fun `목록에 없는 감정이나 강도, 잘못된 JSON은 INVALID_RESPONSE 실패다`() {
        val analyzer = analyzer()
        val invalidBodies =
            listOf(
                """{"emotion":"HAPPY","intensity":"LOW","reason":"x"}""",
                """{"emotion":"ANXIETY","intensity":"EXTREME","reason":"x"}""",
                """{"emotion":"ANXIETY","hp":30,"reason":"x"}""",
                """{"emotion":"ANXIETY","intensity":"LOW"}""",
                """["ANXIETY","LOW"]""",
                "불안해 보입니다",
                """{"emotion":"ANXIETY","intensity":"LOW","reason":"x"""",
            )

        invalidBodies.forEach { invalid ->
            responder.set { 200 to completion(invalid) }
            assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }
                .describedAs(invalid)
                .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                }
        }
    }

    @Test
    fun `응답 JSON을 코드 블록으로 감싸도 파싱한다`() {
        val fenced = "```json\n" + """{"emotion":"IRRITATION","intensity":"HIGH","reason":"화"}""" + "\n```"
        responder.set { 200 to completion(fenced) }

        val result = analyzer().analyze(POST_ID, "짜증나")

        assertThat(result.emotion).isEqualTo(ClassifiedEmotion.IRRITATION)
    }

    @Test
    fun `설정한 타임아웃(기본 20초)을 넘기면 TIMEOUT 실패다`() {
        assertThat(AiProperties().timeout).isEqualTo(Duration.ofSeconds(20))
        responder.set {
            release.await(10, TimeUnit.SECONDS)
            200 to completion(VALID_JSON)
        }
        val analyzer = analyzer(timeout = Duration.ofMillis(300))

        val startedAt = System.nanoTime()
        assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }
            .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.TIMEOUT)
            }
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(5))
    }

    @Test
    fun `서버 오류는 UPSTREAM_ERROR 실패이고, 실패가 쌓여 서킷이 열리면 호출하지 않고 CIRCUIT_OPEN 실패다`() {
        responder.set { 500 to """{"error":{"message":"down"}}""" }
        val analyzer = analyzer()

        repeat(2) {
            assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }
                .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
                }
        }
        val callsBeforeOpen = requests.size

        assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }
            .isInstanceOfSatisfying(EmotionAnalysisFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.CIRCUIT_OPEN)
            }
        assertThat(requests).hasSize(callsBeforeOpen)
    }

    @Test
    fun `모델 응답 원문은 성공해도 실패해도 로그에 남기지 않는다`(output: CapturedOutput) {
        val analyzer = analyzer()
        responder.set { 200 to completion("""{"emotion":"ANXIETY","intensity":"LOW","reason":"$RAW_MARKER"}""") }
        analyzer.analyze(POST_ID, "본문")
        responder.set { 200 to completion("""{"emotion":"HAPPY","intensity":"LOW","reason":"$RAW_MARKER"}""") }
        assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }.isInstanceOf(EmotionAnalysisFailed::class.java)
        responder.set { 200 to completion("not json $RAW_MARKER") }
        assertThatThrownBy { analyzer.analyze(POST_ID, "본문") }.isInstanceOf(EmotionAnalysisFailed::class.java)

        assertThat(output.all).doesNotContain(RAW_MARKER)
    }

    @Test
    fun `서킷 브레이커와 타임아웃의 이름은 emotionAnalyzer다`() {
        assertThat(SpringAiEmotionAnalyzer.RESILIENCE_NAME).isEqualTo("emotionAnalyzer")
    }

    private fun analyzer(timeout: Duration = Duration.ofSeconds(20)): SpringAiEmotionAnalyzer {
        val properties =
            AiProperties(
                baseUrl = URI.create("http://127.0.0.1:${server.address.port}/v1"),
                apiKey = "test-key",
                model = MODEL,
                temperature = 0.1,
                maxTokens = 200,
                timeout = timeout,
                promptVersion = "v1",
            )
        val circuitBreaker =
            CircuitBreaker.of(
                SpringAiEmotionAnalyzer.RESILIENCE_NAME,
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .build(),
            )
        val timeLimiter =
            TimeLimiter.of(
                SpringAiEmotionAnalyzer.RESILIENCE_NAME,
                TimeLimiterConfig.custom().timeoutDuration(timeout).build(),
            )
        return SpringAiEmotionAnalyzer.create(properties, circuitBreaker, timeLimiter).also { analyzers += it }
    }

    private fun completion(content: String): String =
        jsonMapper.writeValueAsString(
            mapOf(
                "id" to "chatcmpl-test",
                "object" to "chat.completion",
                "created" to 1_700_000_000,
                "model" to MODEL,
                "choices" to
                    listOf(
                        mapOf(
                            "index" to 0,
                            "message" to mapOf("role" to "assistant", "content" to content),
                            "finish_reason" to "stop",
                        ),
                    ),
                "usage" to mapOf("prompt_tokens" to 10, "completion_tokens" to 10, "total_tokens" to 20),
            ),
        )

    companion object {
        private const val POST_ID = 1L
        private const val MODEL = "test/model"
        private const val VALID_JSON = """{"emotion":"ANXIETY","intensity":"HIGH","reason":"발표 걱정"}"""
        private const val RAW_MARKER = "RAW-RESPONSE-MARKER-7f3a"
    }
}
