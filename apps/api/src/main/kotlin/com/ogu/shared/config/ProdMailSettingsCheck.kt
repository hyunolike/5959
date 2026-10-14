package com.ogu.shared.config

import com.ogu.shared.mail.MailProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * 운영(prod 프로필)에서 메일 발송을 켰는데(`MAIL_ENABLED=true`) SMTP 주소나 보내는 주소가 비어 있으면 기동을 막는다
 * (015 FR-015). 반쯤 켜진 채로 뜨면 들어가는 길은 보이는데 메일은 영영 가지 않는다. 꺼져 있으면 아무것도 보지 않는다.
 * 링크에 쓸 웹 주소는 `member`의 `ProdAuthSettingsCheck`가 본다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProdMailSettingsCheck(
    properties: MailProperties,
) {
    init {
        if (properties.enabled) {
            check(properties.smtp.host.isNotBlank()) {
                "prod 프로필에서 메일 발송을 켜려면 ogu.mail.smtp.host(MAIL_SMTP_HOST)를 설정해야 합니다."
            }
            check(properties.from.isNotBlank()) {
                "prod 프로필에서 메일 발송을 켜려면 ogu.mail.from(MAIL_FROM)을 설정해야 합니다."
            }
        }
    }
}
