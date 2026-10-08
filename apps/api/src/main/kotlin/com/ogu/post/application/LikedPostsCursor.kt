package com.ogu.post.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * "공감한 글" 키셋 커서(004 research R12). 앞 쪽 마지막 항목의 공감 시각과 글 ID를
 * `base64url("{공감 시각의 에포크 마이크로초}:{postId}")`로 감싼다. DB 정밀도가 마이크로초라 그 단위로 싣는다.
 * 클라이언트에게는 불투명 문자열이다.
 */
internal data class LikedPostsCursor(
    val likedAt: Instant,
    val postId: Long,
) {
    fun encode(): String {
        val micros = ChronoUnit.MICROS.between(Instant.EPOCH, likedAt)
        return ENCODER.encodeToString("$micros:$postId".toByteArray(StandardCharsets.UTF_8))
    }

    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val FORMAT = Regex("^(\\d{1,18}):(\\d{1,19})$")

        /** 형식이 틀리면 400 INVALID_REQUEST. */
        fun decode(raw: String): LikedPostsCursor =
            runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
                .getOrNull()
                ?.let(FORMAT::matchEntire)
                ?.let { match ->
                    val micros = match.groupValues[1].toLongOrNull()
                    val postId = match.groupValues[2].toLongOrNull()
                    if (micros != null && postId != null && postId > 0) {
                        LikedPostsCursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), postId)
                    } else {
                        null
                    }
                }
                ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "공감한 글 커서가 올바르지 않습니다.")
    }
}
