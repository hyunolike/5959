package com.ogu.shared.mail

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSenderImpl

/**
 * [MailSender]의 구현을 고른다(015 research R1).
 *
 * - `ogu.mail.enabled=true`: SMTP로 보낸다. 어느 발송 서비스든 SMTP 주소와 계정만 넣으면 된다.
 * - 꺼져 있으면(기본): 보내지 않고 [MailDeliveryFailed]를 던진다. 꺼진 것을 아는 쪽은 부르기 전에 막는다.
 * - `e2e` 프로필: 아무 데도 보내지 않는 가짜다. 설정과 상관없이 이것을 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MailProperties::class)
class MailConfig {
    @Bean
    @Profile("e2e")
    fun e2eMailSender(): MailSender =
        MailSender {
            log.info("e2e 프로필이라 메일을 보내지 않습니다")
        }

    @Bean
    @Profile("!e2e")
    @ConditionalOnProperty(prefix = "ogu.mail", name = ["enabled"], havingValue = "true")
    fun smtpMailSender(properties: MailProperties): MailSender = SmtpMailSender(properties)

    @Bean
    @Profile("!e2e")
    @ConditionalOnProperty(prefix = "ogu.mail", name = ["enabled"], havingValue = "false", matchIfMissing = true)
    fun disabledMailSender(): MailSender = MailSender { throw MailDeliveryFailed("DISABLED") }

    private companion object {
        val log = LoggerFactory.getLogger(MailConfig::class.java)
    }
}

/**
 * SMTP로 글자 메일을 보낸다. 연결, 읽기, 쓰기에 타임아웃을 건다. 계정이 있으면 인증하고 STARTTLS를 쓴다.
 * 실패의 종류만 남긴다. 발송 서비스의 오류 문구에는 받는 주소가 들어 있을 수 있다.
 */
internal class SmtpMailSender(
    private val properties: MailProperties,
) : MailSender {
    private val sender =
        JavaMailSenderImpl().apply {
            host = properties.smtp.host
            port = properties.smtp.port
            defaultEncoding = Charsets.UTF_8.name()
            if (properties.smtp.username.isNotBlank()) {
                username = properties.smtp.username
                password = properties.smtp.password
            }
            val timeout =
                properties.smtp.timeout
                    .toMillis()
                    .toString()
            javaMailProperties["mail.smtp.auth"] =
                properties.smtp.username
                    .isNotBlank()
                    .toString()
            // 링크 하나로 계정을 넘겨받을 수 있는 메일이다. 서버가 STARTTLS를 내리면 평문으로 보내지 않고 실패한다
            javaMailProperties["mail.smtp.starttls.enable"] = "true"
            javaMailProperties["mail.smtp.starttls.required"] = "true"
            javaMailProperties["mail.smtp.ssl.checkserveridentity"] = "true"
            javaMailProperties["mail.smtp.connectiontimeout"] = timeout
            javaMailProperties["mail.smtp.timeout"] = timeout
            javaMailProperties["mail.smtp.writetimeout"] = timeout
        }

    // 원인의 문구에 받는 주소가 들어 있을 수 있어 예외를 그대로 싣지 않고 종류만 남긴다
    @Suppress("SwallowedException")
    override fun send(mail: Mail) {
        val message =
            SimpleMailMessage().apply {
                from = properties.from
                setTo(mail.to)
                subject = mail.subject
                text = mail.text
            }
        try {
            sender.send(message)
        } catch (e: MailException) {
            throw MailDeliveryFailed(e.javaClass.simpleName)
        }
    }
}
