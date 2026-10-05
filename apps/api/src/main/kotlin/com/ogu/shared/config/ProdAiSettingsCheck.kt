package com.ogu.shared.config

import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 운영(prod 프로필)에서 ogu.ai.api-key(AI_API_KEY), ogu.ai.base-url(AI_BASE_URL), ogu.ai.model(AI_MODEL) 가운데
 * 하나라도 비어 있으면 기동을 막는다. 감정 분석(003-core-loop, Batch 4)이 이 값으로 OpenAI 호환 API를 호출하므로,
 * 하나라도 비면 모든 글의 감정 분석이 영구히 DEFAULTED로 빠진다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdAiSettingsCheck(
    properties: AiProperties,
) {
    init {
        check(properties.apiKey.isNotBlank()) {
            "prod 프로필에서는 ogu.ai.api-key(AI_API_KEY)를 설정해야 합니다."
        }
        check(properties.baseUrl.toString().isNotBlank()) {
            "prod 프로필에서는 ogu.ai.base-url(AI_BASE_URL)을 비워 둘 수 없습니다."
        }
        check(properties.model.isNotBlank()) {
            "prod 프로필에서는 ogu.ai.model(AI_MODEL)을 비워 둘 수 없습니다."
        }
    }
}
