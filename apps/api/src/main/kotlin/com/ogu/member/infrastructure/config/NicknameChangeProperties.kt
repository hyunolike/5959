package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `ogu.member.nickname-change` 설정(014 research R1). 닉네임을 바꾼 뒤 다시 바꿀 수 없는 기간이다.
 * 바꾸면 그 뒤의 변경부터 적용된다. 이미 걸린 제한의 끝나는 때는 그대로다. 웹의 안내 문구("30일")도 함께 바꾼다.
 */
@ConfigurationProperties("ogu.member.nickname-change")
data class NicknameChangeProperties(
    val interval: Duration = Duration.ofDays(30),
)
