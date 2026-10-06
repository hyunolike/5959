package com.ogu.notification.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 알림 목록 키셋 커서(research R12). 앞 쪽 마지막 알림의 번호를 `base64url("{seq}")`로 감싼다. 클라이언트에게는 불투명
 * 문자열이다.
 */
internal object NotificationCursor {
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val FORMAT = Regex("^\\d{1,19}$")
    private const val INVALID_MESSAGE = "알림 커서가 올바르지 않습니다."

    fun encode(lastSeq: Long): String = ENCODER.encodeToString(lastSeq.toString().toByteArray(StandardCharsets.UTF_8))

    /** 형식이 틀리면 400 INVALID_REQUEST. */
    fun decode(raw: String): Long =
        runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
            .getOrNull()
            ?.takeIf(FORMAT::matches)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST, INVALID_MESSAGE)
}
