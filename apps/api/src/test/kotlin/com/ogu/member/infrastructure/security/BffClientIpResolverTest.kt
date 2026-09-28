package com.ogu.member.infrastructure.security

import com.ogu.member.infrastructure.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Duration

class BffClientIpResolverTest {
    @Test
    fun `BFF 키가 설정 값과 같으면 X-Ogu-Client-Ip를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = BFF_KEY))
        val request = request(bffKey = BFF_KEY, clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(CLIENT_IP)
    }

    @Test
    fun `BFF 키가 없으면 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = BFF_KEY))
        val request = request(bffKey = null, clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR)
    }

    @Test
    fun `BFF 키가 다르면 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = BFF_KEY))
        val request = request(bffKey = "wrong-key", clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR)
    }

    @Test
    fun `BFF 키의 앞부분만 같아도 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = BFF_KEY))
        val request = request(bffKey = BFF_KEY.dropLast(1), clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR)
    }

    @Test
    fun `설정 값이 비어 있으면 빈 키를 보내도 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = ""))
        val request = request(bffKey = "", clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR)
    }

    @Test
    fun `설정 값이 공백뿐이면 같은 값을 보내도 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = "   "))
        val request = request(bffKey = "   ", clientIp = CLIENT_IP)

        assertThat(resolver.resolve(request)).isEqualTo(REMOTE_ADDR)
    }

    @Test
    fun `BFF 키가 맞아도 X-Ogu-Client-Ip가 없거나 비어 있으면 원격 주소를 쓴다`() {
        val resolver = BffClientIpResolver(properties(bffKey = BFF_KEY))

        assertThat(resolver.resolve(request(bffKey = BFF_KEY, clientIp = null))).isEqualTo(REMOTE_ADDR)
        assertThat(resolver.resolve(request(bffKey = BFF_KEY, clientIp = " "))).isEqualTo(REMOTE_ADDR)
    }

    private fun request(
        bffKey: String?,
        clientIp: String?,
    ) = MockHttpServletRequest().apply {
        remoteAddr = REMOTE_ADDR
        bffKey?.let { addHeader("X-Ogu-Bff-Key", it) }
        clientIp?.let { addHeader("X-Ogu-Client-Ip", it) }
    }

    private fun properties(bffKey: String) =
        AuthProperties(
            jwt = AuthProperties.Jwt(secret = "unused", accessTokenTtl = Duration.ofMinutes(15)),
            session =
                AuthProperties.Session(
                    idleTtl = Duration.ofDays(14),
                    absoluteTtl = Duration.ofDays(30),
                    rotationGrace = Duration.ofSeconds(30),
                ),
            bffKey = bffKey,
        )

    companion object {
        private const val BFF_KEY = "test-bff-key"
        private const val CLIENT_IP = "203.0.113.7"
        private const val REMOTE_ADDR = "10.0.0.1"
    }
}
