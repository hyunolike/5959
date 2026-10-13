package com.ogu.companion

import com.ogu.ai.infrastructure.CompanionPostValidator
import com.ogu.companion.application.PostTopics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** AI 친구가 글감으로 쓰는 소재 목록(012 research R14). */
class PostTopicsTest {
    @Test
    fun `US3-AC6 소재는 서른 개 이상이고 열쇠가 겹치지 않으며 기록의 열에 들어간다`() {
        val topics = PostTopics.ALL

        assertThat(topics).hasSizeGreaterThanOrEqualTo(30)
        assertThat(topics.map { it.key }).doesNotHaveDuplicates()
        assertThat(topics.map { it.description }).doesNotHaveDuplicates()
        // companion_post.topic은 VARCHAR(40)이다
        assertThat(topics).allSatisfy { assertThat(it.key).matches("[A-Z_]{1,40}") }
        assertThat(PostTopics.find(topics.first().key)).isEqualTo(topics.first())
        assertThat(PostTopics.find("NO_SUCH_TOPIC")).isNull()
    }

    @Test
    fun `US5-AC2 소재의 설명이 그대로 글에 들어가도 글의 규칙에 걸리지 않는다`() {
        // 소재가 규칙에 걸리는 낱말을 담고 있으면 그 소재의 글은 늘 떨어진다
        PostTopics.ALL.forEach { topic ->
            val post = "${topic.description} 어떻게 하면 좋을지 모르겠어요."

            assertThat(CompanionPostValidator.validated(post)).describedAs(topic.key).isEqualTo(post)
        }
    }
}
