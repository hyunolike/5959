package com.ogu.member.application

import com.ogu.member.PasswordResetRequested
import com.ogu.member.domain.AuthMethod
import com.ogu.member.domain.Member
import com.ogu.member.domain.MemberRepository
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.mail.MailProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 비밀번호 재설정 요청(015 US1, US3, research R3, R4). 남용 확인과 회원 조회만 하고 이벤트를 낸다. 링크를 만들고 메일을
 * 보내는 일은 [PasswordResetMailer]가 커밋 뒤에 한다. 그래서 응답이 가입 여부와 발송 장애에 상관없이 같다(FR-002).
 * 링크로 비밀번호를 바꾸는 쪽은 [PasswordResetConfirmation]이다.
 */
@Service
class PasswordResetService(
    private val memberRepository: MemberRepository,
    private val throttle: PasswordResetThrottle,
    private val mail: MailProperties,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    /** 메일 발송이 켜져 있는가. 꺼져 있으면 화면이 들어가는 길을 그리지 않는다(FR-013). */
    fun available(): Boolean = mail.enabled

    /**
     * 꺼져 있으면 `503 PASSWORD_RESET_UNAVAILABLE`, 이메일의 모양이 아니면 `400`, 이 곳의 한도를 넘었으면 `429`다.
     * 그 밖에는 언제나 정상으로 끝난다. 이메일로 가입한 회원이고 이메일의 한도 안일 때만 이벤트에 회원 번호가 실린다.
     * 보낼 회원이 없어도 이벤트는 낸다. 가입한 이메일과 하는 일의 양을 같게 한다.
     */
    fun request(
        rawEmail: String,
        clientIp: String,
    ) {
        if (!mail.enabled) throw BusinessException(ErrorCode.PASSWORD_RESET_UNAVAILABLE)
        val email = Member.normalizeEmail(rawEmail)
        EmailFormat.validate(email)
        throttle.reserveSource(clientIp)
        val allowed = throttle.allowsEmail(email)
        transaction.executeWithoutResult {
            val member = memberRepository.findAllByEmail(email).firstOrNull { it.authMethod == AuthMethod.EMAIL }
            val memberId = member?.id?.takeIf { allowed }
            events.publishEvent(PasswordResetRequested(memberId, clock.instant().truncatedTo(ChronoUnit.MICROS)))
        }
    }
}
