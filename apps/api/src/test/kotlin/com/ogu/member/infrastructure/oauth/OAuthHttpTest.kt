package com.ogu.member.infrastructure.oauth

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.client.ResourceAccessException
import java.io.IOException
import java.net.ConnectException
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * 제공자 호출의 재시도 규칙. 연결 단계에서 실패하면 요청이 제공자에 닿지 않았으므로 한 번 더 보낸다.
 * 응답을 기다리다 끊기면 제공자가 인가 코드를 이미 썼을 수 있어 다시 보내지 않는다.
 */
class OAuthHttpTest {
    @Test
    fun `연결 시간이 지나 실패하면 한 번 더 보내고 두 번째 결과를 쓴다`() {
        var calls = 0

        val result =
            OAuthHttp.call("google-token", ErrorCode.OAUTH_CODE_INVALID) {
                calls++
                if (calls == 1) throw accessFailure(HttpConnectTimeoutException("HTTP connect timed out"))
                "ok"
            }

        assertThat(result).isEqualTo("ok")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `연결이 거절돼도 한 번 더 보낸다`() {
        var calls = 0

        OAuthHttp.call("kakao-token", ErrorCode.OAUTH_CODE_INVALID) {
            calls++
            if (calls == 1) throw accessFailure(ConnectException("Connection refused"))
            "ok"
        }

        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `다시 보내도 연결하지 못하면 두 번에서 멈추고 OAUTH_PROVIDER_UNAVAILABLE`() {
        var calls = 0

        assertThatThrownBy {
            OAuthHttp.call("google-token", ErrorCode.OAUTH_CODE_INVALID) {
                calls++
                throw accessFailure(HttpConnectTimeoutException("HTTP connect timed out"))
            }
        }.extracting { (it as BusinessException).errorCode }
            .isEqualTo(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `응답을 기다리다 시간이 지나면 인가 코드를 두 번 쓰지 않도록 다시 보내지 않는다`() {
        for (cause in listOf(HttpTimeoutException("request timed out"), SocketTimeoutException("Read timed out"))) {
            var calls = 0

            assertThatThrownBy {
                OAuthHttp.call("google-token", ErrorCode.OAUTH_CODE_INVALID) {
                    calls++
                    throw accessFailure(cause)
                }
            }.extracting { (it as BusinessException).errorCode }
                .isEqualTo(ErrorCode.OAUTH_PROVIDER_UNAVAILABLE)
            assertThat(calls).isEqualTo(1)
        }
    }

    @Test
    fun `실제 클라이언트에서 닿지 않는 주소로의 연결 실패는 연결 실패로 본다`() {
        val client = OAuthHttp.restClient(connectTimeout = Duration.ofMillis(200), readTimeout = Duration.ofSeconds(1))

        val failure =
            catchAccessFailure {
                client
                    .get()
                    .uri(URI.create("http://10.255.255.1:81/"))
                    .retrieve()
                    .toBodilessEntity()
            }

        assertThat(OAuthHttp.isConnectFailure(failure)).isTrue()
    }

    @Test
    fun `실제 클라이언트에서 연결 뒤 응답이 늦은 것은 연결 실패로 보지 않는다`() {
        ServerSocket(0).use { silent ->
            // 연결은 받아 주지만 아무 응답도 하지 않는 서버
            val client =
                OAuthHttp.restClient(connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofMillis(300))

            val failure =
                catchAccessFailure {
                    client
                        .get()
                        .uri(URI.create("http://127.0.0.1:${silent.localPort}/"))
                        .retrieve()
                        .toBodilessEntity()
                }

            assertThat(OAuthHttp.isConnectFailure(failure)).isFalse()
        }
    }

    private fun accessFailure(cause: IOException) = ResourceAccessException("I/O error: ${cause.message}", cause)

    private fun catchAccessFailure(block: () -> Unit): ResourceAccessException {
        try {
            block()
        } catch (e: ResourceAccessException) {
            return e
        }
        throw AssertionError("ResourceAccessException이 나야 한다")
    }
}
