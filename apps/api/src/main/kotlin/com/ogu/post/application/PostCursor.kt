package com.ogu.post.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * 피드 키셋 커서(research R7). 앞 쪽 마지막 글의 공감 수와 ID를 `base64url("{likeCount}:{id}")`로 감싼다.
 * 최신순은 ID만 쓰고, 인기순은 둘 다 쓴다. 클라이언트에게는 불투명 문자열이다.
 */
internal data class PostCursor(
    val likeCount: Int,
    val postId: Long,
) {
    fun encode(): String = ENCODER.encodeToString("$likeCount:$postId".toByteArray(StandardCharsets.UTF_8))

    companion object {
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private const val INVALID_MESSAGE = "피드 커서가 올바르지 않습니다."
        private val FORMAT = Regex("^(\\d{1,10}):(\\d{1,19})$")

        /** 형식이 틀리면 400 INVALID_REQUEST. */
        fun decode(raw: String): PostCursor {
            val cursor = parse(raw)
            return cursor ?: throw BusinessException(ErrorCode.INVALID_REQUEST, INVALID_MESSAGE)
        }

        private fun parse(raw: String): PostCursor? =
            runCatching { String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8) }
                .getOrNull()
                ?.let(FORMAT::matchEntire)
                ?.let { match ->
                    val likeCount = match.groupValues[1].toIntOrNull()
                    val postId = match.groupValues[2].toLongOrNull()
                    if (likeCount != null && postId != null && postId > 0) PostCursor(likeCount, postId) else null
                }
    }
}
