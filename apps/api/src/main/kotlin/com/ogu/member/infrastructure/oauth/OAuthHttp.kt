package com.ogu.member.infrastructure.oauth

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.net.http.HttpClient
import java.time.Duration

/**
 * 제공자 호출에 쓰는 HTTP 클라이언트와 오류 변환.
 *
 * 로그에는 단계와 상태 코드, 예외 종류만 남긴다. 인가 코드, access 토큰, `id_token`, 응답 본문은 남기지 않는다.
 */
object OAuthHttp {
    val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
    val READ_TIMEOUT: Duration = Duration.ofSeconds(5)

    private val log = LoggerFactory.getLogger(OAuthHttp::class.java)

    fun restClient(
        connectTimeout: Duration = CONNECT_TIMEOUT,
        readTimeout: Duration = READ_TIMEOUT,
    ): RestClient {
        val httpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(readTimeout) }
        return RestClient.builder().requestFactory(requestFactory).build()
    }

    /**
     * 제공자를 부른다. 4xx는 [onClientError]로, 5xx, 연결 실패, 타임아웃, 해석 실패, 빈 응답은
     * `OAUTH_PROVIDER_UNAVAILABLE`로 바꾼다.
     */
    fun <T : Any> call(
        step: String,
        onClientError: ErrorCode,
        request: () -> T?,
    ): T {
        val result =
            try {
                request()
            } catch (e: RestClientException) {
                throw translate(step, onClientError, e)
            }
        return result ?: throw unavailable(step, "빈 응답")
    }

    private fun translate(
        step: String,
        onClientError: ErrorCode,
        e: RestClientException,
    ): BusinessException =
        if (e is HttpClientErrorException) {
            log.warn("OAuth 제공자가 요청을 거절했습니다: step={}, status={}", step, e.statusCode.value())
            BusinessException(onClientError)
        } else {
            log.warn("OAuth 제공자 호출에 실패했습니다: step={}, error={}", step, e.javaClass.simpleName)
            BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        }

    /** [cause]의 메시지에는 토큰이 들어가지 않는다(nimbus 검증 오류, JWKS 조회 오류). */
    fun unavailable(
        step: String,
        reason: String,
        cause: Throwable? = null,
    ): BusinessException {
        log.warn("OAuth 제공자 응답을 쓸 수 없습니다: step={}, reason={}", step, reason)
        return BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE).apply { cause?.let(::initCause) }
    }

    fun codeInvalid(
        step: String,
        reason: String,
        cause: Throwable? = null,
    ): BusinessException {
        log.warn("OAuth 제공자 응답을 믿을 수 없습니다: step={}, reason={}", step, reason)
        return BusinessException(ErrorCode.OAUTH_CODE_INVALID).apply { cause?.let(::initCause) }
    }
}

/** 문자열 필드. 없거나 문자열이 아니면 null. */
internal fun JsonNode.stringField(name: String): String? = get(name)?.takeIf { it.isString }?.asString()

/** 불리언 필드. 없거나 불리언이 아니면 false. */
internal fun JsonNode.booleanField(name: String): Boolean = get(name)?.let { it.isBoolean && it.booleanValue() } == true
