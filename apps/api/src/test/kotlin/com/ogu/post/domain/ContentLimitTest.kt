package com.ogu.post.domain

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * 글과 댓글 본문 검사(research R8). 코드 포인트 상한을 넘는 긴 입력도 지금과 같은 오류 코드와 메시지를 낸다.
 * 상한을 넘는 입력은 글자 분할을 끝까지 하지 않으므로 큰 입력도 빨리 거절한다.
 */
class ContentLimitTest {
    @Test
    fun `글 본문이 상한보다 훨씬 길면 글자 수 메시지로 거절한다`() {
        assertThatThrownBy { Post.normalizeContent("가".repeat(1_000_000)) }
            .isInstanceOfSatisfying(BusinessException::class.java) {
                assertThat(it.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
            }.hasMessage("본문은 앞뒤 공백을 뺀 1자 이상 500자 이하여야 합니다.")
    }

    @Test
    fun `글 본문의 글자 수는 맞아도 결합 문자가 상한을 넘으면 결합 문자 메시지로 거절한다`() {
        assertThatThrownBy { Post.normalizeContent("a" + "́".repeat(Post.CONTENT_MAX_CODE_POINTS)) }
            .isInstanceOf(BusinessException::class.java)
            .hasMessage("본문에 결합 문자가 너무 많습니다.")
    }

    @Test
    fun `글 본문이 코드 포인트 상한에 딱 맞으면 받아들인다`() {
        val content = "a" + "́".repeat(Post.CONTENT_MAX_CODE_POINTS - 1)

        assertThat(Post.normalizeContent(content)).isEqualTo(content)
    }

    @Test
    fun `댓글이 상한보다 훨씬 길면 글자 수 메시지로 거절한다`() {
        assertThatThrownBy { Comment.normalizeContent("가".repeat(1_000_000)) }
            .isInstanceOf(BusinessException::class.java)
            .hasMessage("댓글은 앞뒤 공백을 뺀 1자 이상 300자 이하여야 합니다.")
    }

    @Test
    fun `댓글의 글자 수는 맞아도 결합 문자가 상한을 넘으면 결합 문자 메시지로 거절한다`() {
        assertThatThrownBy { Comment.normalizeContent("a" + "́".repeat(Comment.CONTENT_MAX_CODE_POINTS)) }
            .isInstanceOf(BusinessException::class.java)
            .hasMessage("댓글에 결합 문자가 너무 많습니다.")
    }
}
