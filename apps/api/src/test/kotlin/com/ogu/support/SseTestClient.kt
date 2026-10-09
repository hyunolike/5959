package com.ogu.support

import org.awaitility.Awaitility.await
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.stream.Stream

/** SSE 이벤트 한 개. [data]는 여러 줄이면 줄바꿈으로 잇는다. */
data class SseEvent(
    val id: String?,
    val event: String?,
    val data: String,
) {
    fun json(): JsonNode = MAPPER.readTree(data)

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()
    }
}

/**
 * 실제 HTTP로 붙은 SSE 스트림(JDK `HttpClient`, `BodyHandlers.ofLines()`). 줄은 백그라운드 스레드가 읽어 [events]와
 * [comments]에 모은다. [close]는 이 스트림만 쓰는 클라이언트를 내려 연결을 끊는다(읽는 스레드가 막혀 있어도 끊긴다).
 */
class SseStream internal constructor(
    private val client: HttpClient,
    val response: HttpResponse<Stream<String>>,
) : AutoCloseable {
    val events = CopyOnWriteArrayList<SseEvent>()
    val comments = CopyOnWriteArrayList<String>()

    @Volatile
    var ended = false
        private set

    val status: Int get() = response.statusCode()

    fun header(name: String): String? = response.headers().firstValue(name).orElse(null)

    /** 200이 아닌 응답의 본문(JSON 오류 봉투). */
    fun errorBody(): JsonNode {
        val text = response.body().use { lines -> lines.toList().joinToString("\n") }
        return JsonMapper.builder().build().readTree(text)
    }

    internal fun startReading() {
        Thread.ofVirtual().name("sse-test-reader").start {
            var id: String? = null
            var event: String? = null
            val data = StringBuilder()
            runCatching {
                response.body().use { lines ->
                    lines.forEach { line ->
                        when {
                            line.isEmpty() -> {
                                if (data.isNotEmpty() || event != null || id != null) {
                                    events += SseEvent(id, event, data.toString().removeSuffix("\n"))
                                }
                                id = null
                                event = null
                                data.setLength(0)
                            }
                            line.startsWith(":") -> comments += line
                            line.startsWith("id:") -> id = line.removePrefix("id:").trim()
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> data.appendLine(line.removePrefix("data:").removePrefix(" "))
                        }
                    }
                }
            }
            ended = true
        }
    }

    /** `event: notification`만 모은 것. */
    fun notifications(): List<SseEvent> = events.filter { it.event == "notification" }

    /** 알림 이벤트가 [count]개 이상 올 때까지 기다린다. */
    fun awaitNotifications(
        count: Int,
        limit: Duration = AWAIT_LIMIT,
    ): List<SseEvent> {
        await().atMost(limit).pollInterval(POLL).until { notifications().size >= count }
        return notifications()
    }

    /** `event: raid`만 모은 것(006). */
    fun raidEvents(): List<SseEvent> = events.filter { it.event == "raid" }

    /** 조건에 맞는 raid 이벤트가 올 때까지 기다리고 그 이벤트를 돌려준다. */
    fun awaitRaid(
        limit: Duration = AWAIT_LIMIT,
        matches: (JsonNode) -> Boolean,
    ): SseEvent {
        await().atMost(limit).pollInterval(POLL).until { raidEvents().any { matches(it.json()) } }
        return raidEvents().first { matches(it.json()) }
    }

    fun awaitEnded(limit: Duration = AWAIT_LIMIT) {
        await().atMost(limit).pollInterval(POLL).until { ended }
    }

    override fun close() {
        client.shutdownNow()
    }

    companion object {
        val AWAIT_LIMIT: Duration = Duration.ofSeconds(15)
        val POLL: Duration = Duration.ofMillis(20)
    }
}

/** 테스트에서 스트림에 붙는다. */
object SseTestClient {
    const val STREAM_PATH = "/api/v1/notifications/stream"

    /**
     * `GET /api/v1/notifications/stream`. [ticket]이 null이면 쿼리에서 뺀다. 응답 헤더가 올 때까지 기다리고, 200이면 줄을
     * 읽기 시작한다.
     */
    fun connect(
        port: Int,
        ticket: String?,
        lastEventId: Long? = null,
        headers: Map<String, String> = emptyMap(),
        topics: String? = null,
    ): SseStream {
        val query =
            listOfNotNull(
                ticket?.let { "ticket=" + URLEncoder.encode(it, Charsets.UTF_8) },
                lastEventId?.let { "lastEventId=$it" },
                topics?.let { "topics=" + URLEncoder.encode(it, Charsets.UTF_8) },
            ).joinToString("&")
        val uri = URI.create("http://localhost:$port$STREAM_PATH" + if (query.isEmpty()) "" else "?$query")
        val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val request =
            HttpRequest
                .newBuilder(uri)
                .header("Accept", "text/event-stream")
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofLines())
        return SseStream(client, response).also { if (response.statusCode() == OK) it.startReading() }
    }

    private const val OK = 200
}
