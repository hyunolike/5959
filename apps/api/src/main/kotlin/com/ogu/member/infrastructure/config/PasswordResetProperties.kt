package com.ogu.member.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `ogu.password-reset` 설정(015).
 *
 * - [linkBase]: 메일의 링크가 가리키는 웹의 주소다. 여기에 `/reset-password?token=...`을 붙인다. 운영에서 메일 발송을
 *   켰으면 https여야 한다.
 * - [ttl]: 링크가 쓰이는 시간.
 * - [mailValidFor]: 요청한 지 이만큼 지난 이벤트는 메일을 보내지 않는다. 재시작으로 한참 뒤에 다시 나간 발행이
 *   뜬금없는 메일이 되지 않게 한다.
 */
@ConfigurationProperties("ogu.password-reset")
data class PasswordResetProperties(
    val linkBase: String = "http://localhost:3000",
    val ttl: Duration = Duration.ofMinutes(30),
    val mailValidFor: Duration = Duration.ofHours(1),
)
