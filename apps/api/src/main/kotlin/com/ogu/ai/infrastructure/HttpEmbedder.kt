package com.ogu.ai.infrastructure

import com.ogu.ai.Embedder
import com.ogu.ai.Embedding
import com.ogu.ai.EmbeddingFailed
import com.ogu.ai.EmotionAnalysisFailed.Kind
import com.ogu.shared.config.AiProperties
import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException

/**
 * OpenAI 호환 `/embeddings`로 임베딩을 만든다(007 research R2). 채팅 모델과 같은 기준 URL과 키를 쓰고 모델만 다르다.
 *
 * - 공급자(NVIDIA)의 모델은 문서와 질의를 나눠 받는다. 글끼리 견주므로 언제나 `input_type = passage`로 보낸다. 이 항목은
 *   OpenAI 표준에 없어 SDK 대신 HTTP로 직접 부른다.
 * - 차원이 설정과 다르면 실패로 본다. 저장하는 열의 차원이 정해져 있다.
 * - 서킷 브레이커 `embedder`를 거친다. 타임아웃은 요청 자체에 건다. 재시도는 recommend 모듈의 일정에 맡긴다.
 * - 본문, 값, 공급자의 응답은 로그에 남기지 않는다. 실패는 분류만 남긴다.
 */
class HttpEmbedder(
    private val properties: AiProperties,
    private val circuitBreaker: CircuitBreaker,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(properties.timeout).build(),
) : Embedder {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jsonMapper = JsonMapper.builder().build()
    private val endpoint = URI.create(properties.baseUrl.toString().trimEnd('/') + "/embeddings")

    override val model: String = properties.embeddingModel

    override fun embed(
        key: String,
        content: String,
    ): Embedding =
        try {
            circuitBreaker.executeCallable { request(content) }
        } catch (e: EmbeddingFailed) {
            fail(key, e.kind, e)
        } catch (e: CallNotPermittedException) {
            fail(key, Kind.CIRCUIT_OPEN, e)
        } catch (e: HttpTimeoutException) {
            fail(key, Kind.TIMEOUT, e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            fail(key, Kind.UPSTREAM_ERROR, e)
        } catch (e: IOException) {
            fail(key, Kind.UPSTREAM_ERROR, e)
        }

    private fun request(content: String): Embedding {
        val body =
            mapOf(
                "model" to properties.embeddingModel,
                "input" to listOf(content),
                "input_type" to INPUT_TYPE,
                "encoding_format" to "float",
            )
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(properties.timeout)
                .header("Authorization", "Bearer ${properties.apiKey}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body)))
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != OK) throw EmbeddingFailed(Kind.UPSTREAM_ERROR)
        return Embedding(properties.embeddingModel, parse(response.body()))
    }

    // 형식이 다른 응답은 어떤 예외든 잘못된 응답으로 본다. 원인에 응답 일부가 들어갈 수 있어 분류만 남긴다
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun parse(body: String): FloatArray {
        val values =
            try {
                val vector =
                    jsonMapper
                        .readTree(body)
                        .path("data")
                        .path(0)
                        .path("embedding")
                FloatArray(vector.size()) { vector.get(it).asDouble().toFloat() }
            } catch (e: RuntimeException) {
                throw EmbeddingFailed(Kind.INVALID_RESPONSE)
            }
        if (values.size != properties.embeddingDimensions) throw EmbeddingFailed(Kind.INVALID_RESPONSE)
        return values
    }

    private fun fail(
        key: String,
        kind: Kind,
        cause: Exception,
    ): Nothing {
        log.warn("임베딩 호출 실패: target={}, kind={}, cause={}", key, kind, cause.javaClass.simpleName)
        throw EmbeddingFailed(kind)
    }

    companion object {
        /** Resilience4j 서킷 브레이커 인스턴스 이름(`resilience4j.circuitbreaker.instances.embedder`). */
        const val RESILIENCE_NAME = "embedder"
        private const val INPUT_TYPE = "passage"
        private const val OK = 200
    }
}
