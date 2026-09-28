package com.ogu.member.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

/**
 * refresh 토큰 생성과 해시(research R1). 원문은 클라이언트에만 주고, 서버에는 SHA-256 hex만 저장한다.
 */
internal object RefreshTokens {
    private const val TOKEN_BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** `SecureRandom` 32바이트를 패딩 없는 base64url로 인코딩한 43자 문자열. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hash(token: String): String =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8)),
        )
}
