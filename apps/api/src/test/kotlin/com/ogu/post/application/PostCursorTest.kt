package com.ogu.post.application

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Base64

/** 피드 커서(research R7): `base64url("{likeCount}:{id}")` 불투명 문자열. */
class PostCursorTest {
    @Test
    fun `공감 수와 글 ID를 base64url로 감싸고 다시 풀면 같다`() {
        val cursor = PostCursor(likeCount = 12, postId = 345)

        val encoded = cursor.encode()

        assertThat(encoded).doesNotContain("=", "+", "/", ":")
        assertThat(String(Base64.getUrlDecoder().decode(encoded))).isEqualTo("12:345")
        assertThat(PostCursor.decode(encoded)).isEqualTo(cursor)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "!!!", "MTI", "YWJj", "MToyOjM", "LTE6NQ", "eDo1", "Mzow", "MTI6"])
    fun `형식이 틀린 커서는 400 INVALID_REQUEST`(raw: String) {
        // MTI=12, YWJj=abc, MToyOjM=1:2:3, LTE6NQ=-1:5, eDo1=x:5, Mzow=3:0, MTI6=12:
        assertThatThrownBy { PostCursor.decode(raw) }
            .isInstanceOf(BusinessException::class.java)
            .extracting("errorCode")
            .isEqualTo(ErrorCode.INVALID_REQUEST)
    }
}
