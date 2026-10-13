package com.ogu.ai.infrastructure

import com.ogu.ai.CompanionPostFailed
import com.ogu.ai.CompanionPostInput
import com.ogu.ai.CompanionTone
import com.ogu.ai.CompanionVoice
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** OpenAI 호환 엔드포인트를 로컬 HTTP 스텁으로 대신해 AI 친구의 글쓰기를 검증한다(012 research R15). */
@ExtendWith(OutputCaptureExtension::class)
class SpringAiCompanionPostWriterTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<String>()
    private val responder = AtomicReference<() -> Pair<Int, String>> { 200 to completion(POST) }
    private lateinit var server: HttpServer
    private val writers = mutableListOf<SpringAiCompanionPostWriter>()

    private val input =
        CompanionPostInput(
            topic = TOPIC,
            tone = CompanionTone.WARM_ADVICE,
            voice = CompanionVoice.CALM,
        )

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
        writers.forEach { it.close() }
        server.stop(0)
    }

    @Test
    fun `US5-AC4 요청에 소재와 말씨와 댓글 말투만 있다`() {
        val post = writer().write(KEY, input)

        assertThat(post).isEqualTo(POST)
        val body = jsonMapper.readTree(requests.single())
        assertThat(body.get("model").asString()).isEqualTo(MODEL)
        assertThat(body.get("max_tokens").asInt()).isEqualTo(1200)
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.8)
        val messages = body.get("messages")
        assertThat(messages).hasSize(2)
        assertThat(messages.get(0).get("role").asString()).isEqualTo("system")
        // 사용자 메시지는 이 세 줄이 전부다. 회원의 글과 댓글, 닉네임이 들어갈 자리가 없다
        assertThat(messages.get(1).get("content").asString()).isEqualTo(
            """
            소재: $TOPIC
            말씨: 차분한 오구
            댓글 말투: 따뜻한 조언해주기
            """.trimIndent(),
        )
        assertThat(requests.single()).doesNotContain(KEY, "COMPANION_POST", "memberId", "nickname", "authorId")
    }

    @Test
    fun `FR-025 시스템 프롬프트가 사람인 척하지 말고 실제 회사와 죽음을 쓰지 말라고 적는다`() {
        writer().write(KEY, input)

        val system =
            jsonMapper
                .readTree(requests.single())
                .get("messages")
                .get(0)
                .get("content")
                .asString()
        assertThat(system)
            .contains("사람이라고 내세우지 않습니다")
            .contains("실제 회사, 기관, 사람의 이름을 쓰지 않습니다")
            .contains("죽음, 자해")
            .contains("회원이나 다른 글, 댓글을 가리켜 말하지 않습니다")
            .contains("500자를 넘기지 않습니다")
    }

    @Test
    fun `US5-AC2 규칙에 맞지 않는 답은 INVALID_RESPONSE이고 답을 로그에 남기지 않는다`(output: CapturedOutput) {
        val broken = listOf("", "가".repeat(501), "$RAW_MARKER 삼성에 다니는 친구가 부러워요. 저는 오늘도 야근이에요.")
        for (answer in broken) {
            responder.set { 200 to completion(answer) }

            assertThatThrownBy { writer().write(KEY, input) }
                .isInstanceOfSatisfying(CompanionPostFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                }
        }
        assertThat(output.all).doesNotContain(RAW_MARKER)
    }

    @Test
    fun `US5-AC7 공급자 오류는 UPSTREAM_ERROR이고 실패가 이어지면 회로가 열려 호출 없이 실패한다`(output: CapturedOutput) {
        responder.set { 500 to """{"error":"$RAW_MARKER"}""" }
        val writer = writer()
        repeat(2) {
            assertThatThrownBy { writer.write(KEY, input) }
                .isInstanceOfSatisfying(CompanionPostFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
                }
        }
        val sent = requests.size

        assertThatThrownBy { writer.write(KEY, input) }
            .isInstanceOfSatisfying(CompanionPostFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.CIRCUIT_OPEN)
            }
        assertThat(requests).hasSize(sent)
        assertThat(output.all).doesNotContain(RAW_MARKER)
    }

    private fun writer(timeout: Duration = Duration.ofSeconds(20)): SpringAiCompanionPostWriter {
        val properties =
            AiProperties(
                baseUrl = URI.create("http://127.0.0.1:${server.address.port}/v1"),
                apiKey = "test-key",
                model = MODEL,
                timeout = timeout,
            )
        val circuitBreaker =
            CircuitBreaker.of(
                SpringAiCompanionPostWriter.RESILIENCE_NAME,
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .build(),
            )
        val timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(timeout).build())
        return SpringAiCompanionPostWriter.create(properties, circuitBreaker, timeLimiter).also { writers += it }
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

    private companion object {
        const val MODEL = "test-model"
        const val KEY = "COMPANION_POST:7"
        const val TOPIC = "회의에서 준비한 말을 끝내 꺼내지 못했다."
        const val POST = "회의에서 준비한 말을 끝내 꺼내지 못했어요. 다들 바빠 보여서 손을 들 틈이 없었어요."
        const val RAW_MARKER = "RAW-MARKER-91be"
    }
}
