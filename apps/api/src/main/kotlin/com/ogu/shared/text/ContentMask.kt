package com.ogu.shared.text

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 다른 회원에게 보여 줄 본문에서 가릴 말을 가린다(005 research R6). 본문을 내보내는 모듈(post, feed, notification)은 이
 * 인터페이스만 보고, 구현은 safety 모듈이 준다. 그래서 post가 safety에 의존하지 않는다.
 * 보는 사람이 작성자면 부르지 않는다(작성자에게는 원문을 보인다).
 */
fun interface ContentMask {
    fun mask(text: String): String
}

/** safety 모듈 없이 띄운 컨텍스트(모듈 테스트)에서는 아무것도 가리지 않는다. */
@Configuration(proxyBeanMethods = false)
class ContentMaskConfig {
    @Bean
    @ConditionalOnMissingBean(ContentMask::class)
    fun noContentMask(): ContentMask = ContentMask { it }
}
