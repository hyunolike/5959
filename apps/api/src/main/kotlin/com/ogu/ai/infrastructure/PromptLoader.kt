package com.ogu.ai.infrastructure

import org.springframework.core.io.ClassPathResource

/** `prompts/emotion-analysis-{version}.st`를 읽는다. 변수가 없는 시스템 프롬프트라 템플릿으로 렌더링하지 않는다. */
object PromptLoader {
    fun load(version: String): String {
        require(version.matches(VERSION_PATTERN)) { "프롬프트 버전 형식이 아닙니다: $version" }
        val resource = ClassPathResource("prompts/emotion-analysis-$version.st")
        check(resource.exists()) { "감정 분석 프롬프트가 없습니다: ${resource.path}" }
        return resource.getContentAsString(Charsets.UTF_8).trim()
    }

    private val VERSION_PATTERN = Regex("v[0-9]+")
}
