package com.ogu.shared.config

import com.ogu.shared.mail.MailProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 운영에서 메일 발송을 켤 때 빠진 값을 막는다(015 US4-AC6). */
class ProdMailSettingsCheckTest {
    private val smtp = MailProperties.Smtp(host = "smtp.example.com")
    private val ready = MailProperties(enabled = true, from = "오구오구 <no-reply@example.com>", smtp = smtp)

    @Test
    fun `US4-AC6 켰는데 SMTP 주소가 비면 뜨지 않는다`() {
        assertThatThrownBy { ProdMailSettingsCheck(ready.copy(smtp = ready.smtp.copy(host = ""))) }
            .hasMessageContaining("MAIL_SMTP_HOST")
    }

    @Test
    fun `US4-AC6 켰는데 보내는 주소가 비면 뜨지 않는다`() {
        assertThatThrownBy { ProdMailSettingsCheck(ready.copy(from = " ")) }.hasMessageContaining("MAIL_FROM")
    }

    @Test
    fun `값이 다 있거나 꺼져 있으면 뜬다`() {
        assertThatCode { ProdMailSettingsCheck(ready) }.doesNotThrowAnyException()
        assertThatCode { ProdMailSettingsCheck(MailProperties()) }.doesNotThrowAnyException()
    }
}
