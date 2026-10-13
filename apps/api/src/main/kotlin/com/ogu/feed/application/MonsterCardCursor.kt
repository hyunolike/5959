package com.ogu.feed.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * 도감 키셋 커서(010 research R6). 앞 쪽 마지막 카드의 처치된 때(마이크로초)와 카드 번호를
 * `base64url("{micros}:{cardNo}")`로 감싼다. 클라이언트에게는 불투명 문자열이다.
 */
internal data class MonsterCardCursor(
    val defeatedAt: Instant,
    val cardNo: Long,
) {
    fun encode(): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, defeatedAt)
        return ENCODER.encodeToString("$micros:$cardNo".toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private const val INVALID_MESSAGE = "도감 커서가 올바르지 않습니다."
        private val FORMAT = Regex("^(\\d{1,18}):(\\d{1,19})$")

        /** 형식이 틀리면 400 INVALID_REQUEST. */
        fun decode(raw: String): MonsterCardCursor {
            val cursor = parse(raw)
            return cursor ?: throw BusinessException(ErrorCode.INVALID_REQUEST, INVALID_MESSAGE)
        }

        private fun parse(raw: String): MonsterCardCursor? =
            runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
                .getOrNull()
                ?.let(FORMAT::matchEntire)
                ?.let { match ->
                    val micros = match.groupValues[1].toLongOrNull()
                    val cardNo = match.groupValues[2].toLongOrNull()
                    if (micros != null && cardNo != null && cardNo > 0) {
                        MonsterCardCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), cardNo)
                    } else {
                        null
                    }
                }
    }
}
