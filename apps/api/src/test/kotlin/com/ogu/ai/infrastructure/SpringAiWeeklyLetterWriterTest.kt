package com.ogu.ai.infrastructure

import com.ogu.ai.ClassifiedEmotion
import com.ogu.ai.EmotionAnalysisFailed
import com.ogu.ai.WeeklyLetterFailed
import com.ogu.ai.WeeklyLetterInput
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

/** OpenAI 호환 엔드포인트를 로컬 HTTP 스텁으로 대신해 편지 쓰기를 검증한다(008 research R7). */
@ExtendWith(OutputCaptureExtension::class)
class SpringAiWeeklyLetterWriterTest {
    private val jsonMapper = JsonMapper.builder().build()
    private val requests = CopyOnWriteArrayList<String>()
    private val responder = AtomicReference<() -> Pair<Int, String>> { 200 to completion(LETTER) }
    private val release = CountDownLatch(1)
    private lateinit var server: HttpServer
    private val writers = mutableListOf<SpringAiWeeklyLetterWriter>()

    private val input =
        WeeklyLetterInput(
            postCount = 3,
            emotionCounts =
                ClassifiedEmotion.entries.associateWith {
                    when (it) {
                        ClassifiedEmotion.ANXIETY -> 2
                        ClassifiedEmotion.IRRITATION -> 1
                        else -> 0
                    }
                },
            unanalyzedCount = 0,
            topEmotion = ClassifiedEmotion.ANXIETY,
            defeatedCount = 1,
            receivedLikes = 12,
            receivedComments = 4,
            previousPostCount = 5,
            previousTopEmotion = ClassifiedEmotion.LETHARGY,
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
        release.countDown()
        writers.forEach { it.close() }
        server.stop(0)
    }

    @Test
    fun `US2-AC5 요청에는 시스템 프롬프트와 수치만 있다`() {
        val letter = writer().write(KEY, input)

        assertThat(letter).isEqualTo(LETTER)
        val body = jsonMapper.readTree(requests.single())
        assertThat(body.get("model").asString()).isEqualTo(MODEL)
        assertThat(body.get("max_tokens").asInt()).isEqualTo(900)
        val messages = body.get("messages")
        assertThat(messages).hasSize(2)
        assertThat(messages.get(0).get("role").asString()).isEqualTo("system")
        assertThat(messages.get(0).get("content").asString()).contains("존댓말", "진단하지 않습니다")
        // 사용자 메시지는 이 여덟 줄이 전부다. 본문, 닉네임, 직군, 경력, 회원 번호가 들어갈 자리가 없다
        assertThat(messages.get(1).get("content").asString()).isEqualTo(
            """
            쓴 글 수: 3
            감정별 글 수: 불안 2, 짜증 1
            가장 많은 감정: 불안
            처치된 몬스터 수: 1
            받은 공감 수: 12
            받은 댓글 수: 4
            앞 주의 글 수: 5
            앞 주의 가장 많은 감정: 무기력
            """.trimIndent(),
        )
        // 요청 어디에도 리포트나 회원을 가리키는 값이 없다
        assertThat(requests.single()).doesNotContain(KEY, "REPORT", "memberId", "nickname")
    }

    @Test
    fun `앞 주의 리포트와 분석된 글이 없으면 그 줄을 보내지 않는다`() {
        val bare =
            WeeklyLetterInput(
                postCount = 1,
                emotionCounts = emptyMap(),
                unanalyzedCount = 1,
                topEmotion = null,
                defeatedCount = 0,
                receivedLikes = 0,
                receivedComments = 0,
            )
        responder.set { 200 to completion("글 1개를 남겨 주셨어요.") }

        writer().write(KEY, bare)

        val content =
            jsonMapper
                .readTree(requests.single())
                .get("messages")
                .get(1)
                .get("content")
                .asString()
        assertThat(content).contains("감정별 글 수: 분류된 글 없음", "가장 많은 감정: 없음").doesNotContain("앞 주")
    }

    @Test
    fun `US2-AC7 규칙에 맞지 않는 답은 INVALID_RESPONSE이고 답을 로그에 남기지 않는다`(output: CapturedOutput) {
        val writer = writer()
        listOf("", "공감을 99개 받으셨어요. $RAW_MARKER", "가".repeat(301)).forEach { raw ->
            responder.set { 200 to completion(raw) }
            assertThatThrownBy { writer.write(KEY, input) }
                .isInstanceOfSatisfying(WeeklyLetterFailed::class.java) {
                    assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.INVALID_RESPONSE)
                }
        }
        assertThat(output.all).doesNotContain(RAW_MARKER)
    }

    @Test
    fun `공급자 오류는 UPSTREAM_ERROR이고 응답을 로그에 남기지 않는다`(output: CapturedOutput) {
        responder.set { 500 to """{"error":"$RAW_MARKER"}""" }

        assertThatThrownBy { writer().write(KEY, input) }
            .isInstanceOfSatisfying(WeeklyLetterFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.UPSTREAM_ERROR)
            }
        assertThat(output.all).doesNotContain(RAW_MARKER)
    }

    @Test
    fun `응답이 늦으면 TIMEOUT이다`() {
        responder.set {
            release.await(5, TimeUnit.SECONDS)
            200 to completion(LETTER)
        }

        assertThatThrownBy { writer(timeout = Duration.ofMillis(300)).write(KEY, input) }
            .isInstanceOfSatisfying(WeeklyLetterFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.TIMEOUT)
            }
    }

    @Test
    fun `실패가 이어지면 서킷이 열려 호출 없이 실패한다`() {
        responder.set { 500 to "{}" }
        val writer = writer()
        repeat(2) { assertThatThrownBy { writer.write(KEY, input) }.isInstanceOf(WeeklyLetterFailed::class.java) }
        val sent = requests.size

        assertThatThrownBy { writer.write(KEY, input) }
            .isInstanceOfSatisfying(WeeklyLetterFailed::class.java) {
                assertThat(it.kind).isEqualTo(EmotionAnalysisFailed.Kind.CIRCUIT_OPEN)
            }
        assertThat(requests).hasSize(sent)
    }

    private fun writer(timeout: Duration = Duration.ofSeconds(20)): SpringAiWeeklyLetterWriter {
        val properties =
            AiProperties(
                baseUrl = URI.create("http://127.0.0.1:${server.address.port}/v1"),
                apiKey = "test-key",
                model = MODEL,
                timeout = timeout,
            )
        val circuitBreaker =
            CircuitBreaker.of(
                SpringAiWeeklyLetterWriter.RESILIENCE_NAME,
                CircuitBreakerConfig
                    .custom()
                    .slidingWindowSize(2)
                    .minimumNumberOfCalls(2)
                    .failureRateThreshold(50f)
                    .waitDurationInOpenState(Duration.ofMinutes(1))
                    .build(),
            )
        val timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom().timeoutDuration(timeout).build())
        return SpringAiWeeklyLetterWriter.create(properties, circuitBreaker, timeLimiter, 300).also { writers += it }
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
        private const val KEY = "REPORT:77"
        private const val MODEL = "test/model"
        private const val LETTER = "지난주에 글을 3개 남기셨고, 공감 12개와 댓글 4개가 곁에 있었어요."
        private const val RAW_MARKER = "RAW-LETTER-MARKER-5c1d"
    }
}
