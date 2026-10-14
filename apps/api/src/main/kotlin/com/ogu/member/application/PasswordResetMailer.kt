package com.ogu.member.application

import com.ogu.member.PasswordResetRequested
import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.MemberRepository
import com.ogu.member.domain.PasswordResetTokenRepository
import com.ogu.member.infrastructure.config.PasswordResetProperties
import com.ogu.shared.mail.Mail
import com.ogu.shared.mail.MailSender
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.net.URLEncoder
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 재설정 요청을 커밋 뒤에 받아 링크를 만들고 메일을 보낸다(015 research R3). 트랜잭션 밖에서 돈다. 링크를 저장하는 일만
 * 짧은 트랜잭션([ResetLinkIssuer])이고, 발송 서비스를 기다리는 동안 DB 연결을 쥐지 않는다.
 *
 * - 발송이 실패하면 예외로 끝나 발행이 끝나지 않은 것으로 남고 다시 온다. 다시 올 때 새 값을 만들어 덮어쓴다. 앞의
 *   시도에서 저장만 되고 보내지 못한 값은 그렇게 쓸 수 없게 된다(FR-014).
 * - 요청한 지 [PasswordResetProperties.mailValidFor]가 지난 이벤트는 보내지 않고 끝낸다. 재시작으로 한참 뒤에 다시
 *   나간 발행이 뜬금없는 메일이 되지 않게 한다.
 *
 * 로그에는 회원 번호만 남긴다. 이메일, 링크, 본문을 남기지 않는다.
 */
@Component
class PasswordResetMailer(
    private val issuer: ResetLinkIssuer,
    private val mailSender: MailSender,
    private val properties: PasswordResetProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener(propagation = Propagation.NOT_SUPPORTED)
    fun on(event: PasswordResetRequested) {
        val memberId = event.memberId
        if (memberId == null || isStale(event)) return
        val issued = issuer.issue(memberId) ?: return
        mailSender.send(PasswordResetMail.of(issued.email, link(issued.token), properties.ttl.toMinutes()))
        log.info("비밀번호 재설정 메일을 보냈습니다: memberId={}", memberId)
    }

    private fun isStale(event: PasswordResetRequested): Boolean {
        val stale = clock.instant().isAfter(event.requestedAt.plus(properties.mailValidFor))
        if (stale) log.warn("요청한 지 오래된 비밀번호 재설정 메일은 보내지 않습니다: memberId={}", event.memberId)
        return stale
    }

    private fun link(token: String): String =
        properties.linkBase.trimEnd('/') + "/reset-password?token=" + URLEncoder.encode(token, Charsets.UTF_8)
}

/**
 * 회원의 재설정 링크를 새로 만들어 저장하고 원문을 돌려준다(015 research R2). 링크의 값은 여기서 만든다. 이벤트에 실으면
 * 발행 기록에 원문이 남는다(FR-010). 회원을 다시 읽어, 그사이 탈퇴했거나 이메일로 가입한 회원이 아니면 null이다.
 */
@Component
class ResetLinkIssuer(
    private val memberRepository: MemberRepository,
    private val tokens: PasswordResetTokenRepository,
    private val tokenGenerator: ResetTokenGenerator,
    private val properties: PasswordResetProperties,
    private val clock: Clock,
) {
    @Transactional
    fun issue(memberId: Long): IssuedResetLink? {
        val member =
            memberRepository
                .findByIdOrNull(memberId)
                ?.takeIf { it.authMethod == AuthMethod.EMAIL && !it.isWithdrawn }
        val email = member?.email ?: return null
        val token = tokenGenerator.generate(email)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        tokens.replace(memberId, RefreshTokens.hash(token), now, now.plus(properties.ttl))
        return IssuedResetLink(email, token)
    }
}

data class IssuedResetLink(
    val email: String,
    val token: String,
)

/** 재설정 메일의 문구(015 FR-011). 서비스의 이름, 링크, 쓰이는 시간, 무시해도 된다는 안내뿐이다. */
internal object PasswordResetMail {
    const val SUBJECT = "[오구오구] 비밀번호 재설정 안내"

    fun of(
        to: String,
        link: String,
        validMinutes: Long,
    ): Mail =
        Mail(
            to = to,
            subject = SUBJECT,
            text =
                """
                오구오구에서 비밀번호 재설정을 요청받았습니다.

                아래 링크를 열어 새 비밀번호를 정해 주세요.
                $link

                이 링크는 ${validMinutes}분 동안, 한 번만 쓸 수 있습니다.
                요청한 적이 없다면 이 메일을 무시해도 됩니다. 비밀번호는 바뀌지 않습니다.
                """.trimIndent(),
        )
}
