package com.ogu.post

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Instant

class MyCommentPageTest {
    private val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    @Test
    fun `내 댓글의 답글 여부는 계약대로 reply라는 이름으로 나간다`() {
        val comment =
            MyComment(
                commentId = 1L,
                postId = 2L,
                postContentPreview = "글 앞부분",
                content = "답글",
                isReply = true,
                createdAt = Instant.parse("2026-10-05T00:00:00Z"),
            )

        val json = mapper.readTree(mapper.writeValueAsString(MyCommentPage(listOf(comment), nextCursor = null)))

        val item = json["items"][0]
        assertThat(item["reply"].asBoolean()).isTrue()
        assertThat(item.has("isReply")).isFalse()
        assertThat(item.propertyNames()).containsExactlyInAnyOrder(
            "commentId",
            "postId",
            "postContentPreview",
            "content",
            "reply",
            "createdAt",
        )
    }
}
