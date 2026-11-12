package com.ogu.member.infrastructure.oauth

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.slf4j.LoggerFactory
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.JsonNode
import java.net.ConnectException
import java.net.http.HttpClient
import java.net.http.HttpConnectTimeoutException
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
     * 제공자를 부른다. 400(`invalid_grant` 등 요청 값 문제)은 [onBadRequest]로 바꾼다. 401(`invalid_client`), 403,
     * 429 같은 다른 4xx는 우리 설정이나 호출 한도 문제이므로, 5xx, 연결 실패, 타임아웃, 해석 실패, 빈 응답과 함께
     * `OAUTH_PROVIDER_UNAVAILABLE`로 바꾼다. 로그에는 상태 코드만 남기고 응답 본문은 남기지 않는다.
     *
     * 연결 단계에서 실패하면([isConnectFailure]) 한 번 더 보낸다. 요청이 제공자에 닿지 않았으니 인가 코드를 두 번 쓰지
     * 않는다. 잠들었다 막 깬 서버는 첫 TLS 연결이 연결 시간 안에 끝나지 않을 때가 있다.
     * 응답을 기다리다 끊긴 것은 제공자가 코드를 이미 썼을 수 있어 다시 보내지 않는다.
     */
    fun <T : Any> call(
        step: String,
        onBadRequest: ErrorCode,
        request: () -> T?,
    ): T {
        val result =
            try {
                retryOnConnectFailure(step, request)
            } catch (e: RestClientException) {
                throw translate(step, onBadRequest, e)
            }
        return result ?: throw unavailable(step, "빈 응답")
    }

    /** 연결 단계의 실패만 한 번 더 보낸다. 다른 실패와 두 번째 실패는 그대로 던진다. */
    private fun <T : Any> retryOnConnectFailure(
        step: String,
        request: () -> T?,
    ): T? =
        try {
            request()
        } catch (e: RestClientException) {
            if (!isConnectFailure(e)) throw e
            log.warn("OAuth 제공자에 연결하지 못해 한 번 더 보냅니다: step={}, error={}", step, rootName(e))
            request()
        }

    /** 연결을 맺지 못한 실패(연결 시간 초과, 연결 거절). 요청 본문은 제공자에 가지 않았다. */
    fun isConnectFailure(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { it is HttpConnectTimeoutException || it is ConnectException }

    private fun rootName(e: Throwable): String = generateSequence(e) { it.cause }.last().javaClass.simpleName

    private fun translate(
        step: String,
        onBadRequest: ErrorCode,
        e: RestClientException,
    ): BusinessException =
        if (e is HttpClientErrorException.BadRequest) {
            log.warn("OAuth 제공자가 요청을 거절했습니다: step={}, status={}", step, e.statusCode.value())
            BusinessException(onBadRequest)
        } else if (e is HttpClientErrorException) {
            log.warn("OAuth 제공자가 우리 요청을 받지 않습니다: step={}, status={}", step, e.statusCode.value())
            BusinessException(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        } else {
            log.warn(
                "OAuth 제공자 호출에 실패했습니다: step={}, error={}, cause={}",
                step,
                e.javaClass.simpleName,
                rootName(e),
            )
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
