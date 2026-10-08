package com.ogu.shared.text

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ContentMaskConfigTest {
    private val runner = ApplicationContextRunner().withUserConfiguration(ContentMaskConfig::class.java)

    @Test
    fun `구현이 없으면 아무것도 가리지 않는 기본 구현을 쓴다`() {
        runner.run { context ->
            val mask = context.getBean(ContentMask::class.java)

            assertThat(mask.mask("그대로 나가는 글")).isEqualTo("그대로 나가는 글")
        }
    }

    @Test
    fun `다른 구현이 있으면 기본 구현을 만들지 않는다`() {
        runner.withBean(ContentMask::class.java, { ContentMask { "가림" } }).run { context ->
            assertThat(context.getBeansOfType(ContentMask::class.java)).hasSize(1)
            assertThat(context.getBean(ContentMask::class.java).mask("원문")).isEqualTo("가림")
        }
    }
}
