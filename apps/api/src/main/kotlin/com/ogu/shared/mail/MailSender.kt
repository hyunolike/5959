package com.ogu.shared.mail

/**
 * 메일 한 통(015 research R1). 글자만 든 메일이다. 받는 주소와 본문은 로그에 남기지 않는다.
 */
data class Mail(
    val to: String,
    val subject: String,
    val text: String,
)

/**
 * 메일을 보내는 포트. 구현은 [MailConfig]가 설정에 따라 고른다: SMTP, 꺼짐, `e2e` 프로필의 가짜.
 * 보내지 못하면 [MailDeliveryFailed]를 던진다. 부르는 쪽은 요청 스레드가 아니라 커밋 뒤 리스너에서 부른다.
 * 발송 서비스가 느리거나 내려가 있어도 요청이 기다리지 않게 한다.
 */
fun interface MailSender {
    fun send(mail: Mail)
}

/** 메일을 보내지 못했다. 메시지에 받는 주소와 본문을 싣지 않는다. 원인의 종류만 남긴다. */
class MailDeliveryFailed(
    kind: String,
) : RuntimeException("메일을 보내지 못했습니다: $kind")
