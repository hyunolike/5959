package com.ogu.member.infrastructure.security

import com.ogu.member.infrastructure.config.AuthProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * 로그인 실패 제한에 쓸 클라이언트 IP를 정한다(research R6).
 *
 * API는 BFF(Vercel 함수)를 거친 요청만 받으므로 원격 주소는 BFF의 주소다. BFF가 비밀 키 `X-Ogu-Bff-Key`와 함께
 * 보낸 `X-Ogu-Client-Ip`만 믿고, 그 밖의 경우에는 원격 주소를 쓴다. API에 직접 요청하면서 IP를 위조해 차단을
 * 피하는 것을 막기 위해서다.
 */
@Component
class BffClientIpResolver(
    properties: AuthProperties,
) {
    // 키 길이가 비교 시간으로 드러나지 않도록 양쪽을 SHA-256으로 같은 길이로 만든 뒤 상수 시간으로 비교한다.
    private val expectedKeyDigest: ByteArray? =
        properties.bffKey
            .takeIf { it.isNotBlank() }
            ?.let { sha256(it) }

    fun resolve(request: HttpServletRequest): String {
        val clientIp = request.getHeader(CLIENT_IP_HEADER)?.trim()
        return if (isFromBff(request) && !clientIp.isNullOrEmpty()) clientIp else request.remoteAddr
    }

    private fun isFromBff(request: HttpServletRequest): Boolean {
        val expected = expectedKeyDigest
        val providedKey = request.getHeader(BFF_KEY_HEADER)
        return expected != null && providedKey != null && MessageDigest.isEqual(expected, sha256(providedKey))
    }

    private fun sha256(value: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(value.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val BFF_KEY_HEADER = "X-Ogu-Bff-Key"
        const val CLIENT_IP_HEADER = "X-Ogu-Client-Ip"
    }
}
