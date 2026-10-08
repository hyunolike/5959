package com.ogu.post.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 댓글 목록 키셋 커서. 앞 쪽 마지막 원 댓글 ID를 `base64url("{id}")`로 감싼다. 클라이언트에게는 불투명 문자열이다.
 */
internal object CommentCursor {
    private val ENCODER = Base64.getUrlEncoder().withoutPadding()
    private val FORMAT = Regex("^\\d{1,19}$")

    fun encode(lastCommentId: Long): String {
        val bytes = lastCommentId.toString().toByteArray(StandardCharsets.UTF_8)
        return ENCODER.encodeToString(bytes)
    }

    /** 형식이 틀리면 400 INVALID_REQUEST. */
    fun decode(raw: String): Long =
        runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
            .getOrNull()
            ?.takeIf(FORMAT::matches)
            ?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST, "댓글 커서가 올바르지 않습니다.")
}
