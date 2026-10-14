package com.ogu.member.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.member.infrastructure.config.AuthProperties
import com.ogu.member.infrastructure.security.BffClientIpResolver
import com.ogu.shared.config.EventPublicationResubmitter
import com.ogu.shared.mail.Mail
import com.ogu.shared.mail.MailDeliveryFailed
import com.ogu.shared.mail.MailSender
import com.ogu.support.MemberFixture
import com.ogu.support.TestAccount
import com.ogu.support.TestMember
import com.ogu.support.WithdrawalFixture
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.net.URLDecoder
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * 비밀번호 재설정(015 US1~US4). 메일 발송을 켠 설정이고, 발송은 받은 메일을 모으는 가짜가 대신한다. 메일은 커밋 뒤
 * 리스너가 보내므로 도착을 기다린다. 정기 재전송은 끄고 [EventPublicationResubmitter.resubmit]을 직접 부른다.
 */
@SpringBootTest(
    properties = [
        "ogu.mail.enabled=true",
        "ogu.password-reset.link-base=https://web.ogu.test",
        "ogu.events.resubmit.enabled=false",
        "ogu.events.resubmit.older-than=0s",
    ],
)
@Import(TestcontainersConfiguration::class)
// 메일을 켠 이 클래스만의 설정이다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class PasswordResetApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var authProperties: AuthProperties

    @Autowired
    lateinit var resubmitter: EventPublicationResubmitter

    @MockitoBean
    lateinit var mailSender: MailSender

    private val jsonMapper = JsonMapper.builder().build()
    private val sent = CopyOnWriteArrayList<Mail>()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var withdrawal: WithdrawalFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
        sent.clear()
        doAnswer { sent += it.getArgument<Mail>(0) }.`when`(mailSender).send(anyMail())
    }

    @Test
    fun `US4-AC3 켜져 있으면 available이 true다`() {
        mockMvc
            .perform(get(PATH))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    fun `US1-AC2 US1-AC7 이메일 가입 회원에게 링크가 든 메일이 하나 가고 닉네임과 비밀번호는 없다`() {
        val account = withdrawal.onboarded()

        request(account.email).andExpect(status().isNoContent)

        val mail = awaitMail(account.email)
        assertThat(mail.subject).isEqualTo("[오구오구] 비밀번호 재설정 안내")
        assertThat(mail.text)
            .contains("오구오구")
            .contains("https://web.ogu.test/reset-password?token=")
            .contains("30분 동안, 한 번만")
            .contains("요청한 적이 없다면 이 메일을 무시해도 됩니다")
            .doesNotContain(WithdrawalFixture.PASSWORD)
        val nicknameSql = "select nickname from member where id = ?"
        val nickname = jdbcTemplate.queryForObject(nicknameSql, String::class.java, account.member.id)
        assertThat(mail.text).doesNotContain(nickname)
        assertThat(sent.filter { it.to == account.email }).hasSize(1)
    }

    @Test
    fun `US1-AC3 US1-AC4 없는 이메일, 소셜 회원, 탈퇴한 회원에게는 같은 204이고 메일이 가지 않는다`() {
        val withdrawn = withdrawal.onboarded()
        withdrawal.withdrawAndClean(withdrawn.member)
        val social = "social-${UUID.randomUUID()}@example.com"
        jdbcTemplate.update(
            "insert into member (auth_method, email, created_at, updated_at) values ('KAKAO', ?, now(), now())",
            social,
        )
        val known = withdrawal.signUp()

        val responses =
            listOf("nobody-${UUID.randomUUID()}@example.com", social, withdrawn.email, known.email).map {
                request(it)
                    .andExpect(status().isNoContent)
                    .andReturn()
                    .response.contentAsString
            }

        // 응답의 내용이 모두 같다(US3-AC3)
        assertThat(responses.toSet()).hasSize(1)
        awaitMail(known.email)
        assertThat(sent.map { it.to }).containsExactly(known.email)
    }

    @Test
    fun `US1-AC6 이메일의 모양이 아니면 400이다`() {
        request("not-an-email")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        mockMvc
            .perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `US1-AC8 대문자와 공백을 섞어도 같은 회원을 찾는다`() {
        val account = withdrawal.signUp()

        request("  ${account.email.uppercase()} ").andExpect(status().isNoContent)

        awaitMail(account.email)
    }

    @Test
    fun `US2-AC1 US2-AC2 US2-AC7 새 비밀번호로 로그인되고 옛 비밀번호와 다른 기기의 토큰은 안 된다`() {
        val account = withdrawal.onboarded()
        val otherDevice = withdrawal.loginAgain(account)

        confirm(tokenFor(account), NEW_PASSWORD).andExpect(status().isNoContent)

        withdrawal.login(account.email, NEW_PASSWORD).andExpect(status().isOk)
        withdrawal
            .login(account.email, WithdrawalFixture.PASSWORD)
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_CREDENTIALS"))
        me(otherDevice.member).andExpect(status().isUnauthorized)
        me(account.member).andExpect(status().isUnauthorized)
    }

    @Test
    fun `US2-AC3 한 번 쓴 링크는 다시 쓸 수 없다`() {
        val account = withdrawal.signUp()
        val token = tokenFor(account)
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent)

        confirm(token, "another123")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_LINK_INVALID"))
        withdrawal.login(account.email, NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `US2-AC4 30분이 지난 링크는 쓸 수 없다`() {
        val account = withdrawal.signUp()
        val token = tokenFor(account)
        jdbcTemplate.update(
            "update password_reset_token set created_at = now() - interval '31 minutes', " +
                "expires_at = now() - interval '1 minute' where member_id = ?",
            account.member.id,
        )

        confirm(token, NEW_PASSWORD).andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_LINK_INVALID"))
        withdrawal.login(account.email, WithdrawalFixture.PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `US2-AC5 새로 요청하면 앞의 링크는 쓸 수 없다`() {
        val account = withdrawal.signUp()
        val first = tokenFor(account)
        sent.clear()
        val second = tokenFor(account)

        confirm(first, NEW_PASSWORD).andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_LINK_INVALID"))
        confirm(second, NEW_PASSWORD).andExpect(status().isNoContent)
    }

    @Test
    fun `US2-AC6 규칙에 맞지 않는 비밀번호는 400이고 링크는 그대로 쓸 수 있다`() {
        val account = withdrawal.signUp()
        val token = tokenFor(account)

        confirm(token, "short1")
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        confirm(token, "onlyletters").andExpect(status().isBadRequest)

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent)
    }

    @Test
    fun `US2-AC8 로그인이 막혀 있던 회원도 새 비밀번호로 바로 로그인한다`() {
        val account = withdrawal.signUp()
        repeat(LOGIN_LIMIT + 1) { loginFrom(account.email, "wrongpass123") }
        loginFrom(account.email, WithdrawalFixture.PASSWORD).andExpect(status().isTooManyRequests)

        confirm(tokenFor(account), NEW_PASSWORD).andExpect(status().isNoContent)

        loginFrom(account.email, NEW_PASSWORD).andExpect(status().isOk)
    }

    @Test
    fun `US2-AC9 지어낸 값과 빈 값은 같은 400이다`() {
        val made = confirm("made-up-${UUID.randomUUID()}", NEW_PASSWORD).andReturn().response.contentAsString
        val empty = confirm("", NEW_PASSWORD).andReturn().response.contentAsString

        assertThat(made).contains("PASSWORD_RESET_LINK_INVALID").isEqualTo(empty)
    }

    @Test
    fun `US2-AC10 같은 링크의 겹친 요청은 하나만 받는다`() {
        val account = withdrawal.signUp()
        val token = tokenFor(account)

        val results =
            List(RACERS) {
                CompletableFuture.supplyAsync { confirm(token, NEW_PASSWORD).andReturn().response.status }
            }.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }

        assertThat(results.count { it == NO_CONTENT }).isEqualTo(1)
        assertThat(results.count { it == BAD_REQUEST }).isEqualTo(RACERS - 1)
    }

    @Test
    fun `US3-AC1 같은 이메일의 네 번째 요청은 204이고 메일이 가지 않는다`() {
        val account = withdrawal.signUp()

        repeat(EMAIL_LIMIT + 1) { request(account.email).andExpect(status().isNoContent) }

        await().atMost(AWAIT).until { sent.count { it.to == account.email } == EMAIL_LIMIT }
        Thread.sleep(SETTLE_MILLIS)
        assertThat(sent.count { it.to == account.email }).isEqualTo(EMAIL_LIMIT)
    }

    @Test
    fun `US3-AC2 같은 곳의 열한 번째 요청은 429와 남은 시간이다`() {
        val ip = uniqueIp()

        repeat(SOURCE_LIMIT) {
            request("someone-$it-${UUID.randomUUID()}@example.com", ip).andExpect(status().isNoContent)
        }

        request("one-more-${UUID.randomUUID()}@example.com", ip)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_THROTTLED"))
            .andExpect(header().exists("Retry-After"))
    }

    @Test
    fun `US3-AC4 FR-010 발행 기록과 링크 테이블에 이메일과 링크의 원문이 없다`() {
        val account = withdrawal.signUp()
        val token = tokenFor(account)

        val events =
            jdbcTemplate.queryForList(
                "select serialized_event from event_publication " +
                    "where event_type = 'com.ogu.member.PasswordResetRequested'",
                String::class.java,
            )
        assertThat(events).isNotEmpty.noneMatch { it.contains(account.email) || it.contains(token) }
        val stored =
            jdbcTemplate.queryForObject(
                "select token_hash from password_reset_token where member_id = ?",
                String::class.java,
                account.member.id,
            )
        assertThat(stored).hasSize(64).isNotEqualTo(token)
        val keys =
            jdbcTemplate.queryForList(
                "select scope_key from login_attempt where scope_key like 'reset-%'",
                String::class.java,
            )
        assertThat(keys).noneMatch { it.contains(account.email) }
    }

    @Test
    fun `US3-AC5 탈퇴 전에 받은 링크는 쓸 수 없다`() {
        val account = withdrawal.onboarded()
        val token = tokenFor(account)

        withdrawal.withdrawAndClean(account.member)

        confirm(token, NEW_PASSWORD).andExpect(jsonPath("$.error.code").value("PASSWORD_RESET_LINK_INVALID"))
    }

    @Test
    fun `US4-AC4 US4-AC5 발송이 실패해도 요청은 204이고 다시 보낸 링크를 쓸 수 있다`() {
        val account = withdrawal.signUp()
        doThrow(MailDeliveryFailed("SMTP_DOWN"))
            .doAnswer { sent += it.getArgument<Mail>(0) }
            .`when`(mailSender)
            .send(anyMail())

        request(account.email).andExpect(status().isNoContent)
        await().atMost(AWAIT).until { pendingResetMails() > 0 }
        assertThat(sent).isEmpty()

        resubmitter.resubmit()

        val mail = awaitMail(account.email)
        confirm(tokenOf(mail), NEW_PASSWORD).andExpect(status().isNoContent)
    }

    @Test
    fun `US4-AC7 메일 발송 서비스의 상태가 서비스의 상태 확인에 들지 않는다`() {
        // Boot의 메일 자동 설정이 만드는 JavaMailSender 빈이 없으면 메일 헬스 지표도 없다
        assertThat(applicationContext.getBeanNamesForType(JavaMailSender::class.java)).isEmpty()
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk)
    }

    /** Kotlin에서 Mockito의 `any()`는 null을 돌려준다. 널이 아닌 매개변수에 넘기려면 자리만 채운다. */
    private fun anyMail(): Mail = any(Mail::class.java) ?: Mail("", "", "")

    private fun tokenFor(account: TestAccount): String {
        request(account.email).andExpect(status().isNoContent)
        return tokenOf(awaitMail(account.email))
    }

    private fun tokenOf(mail: Mail): String {
        val encoded = Regex("token=([^\\s]+)").find(mail.text)!!.groupValues[1]
        return URLDecoder.decode(encoded, Charsets.UTF_8)
    }

    private fun awaitMail(email: String): Mail {
        await().atMost(AWAIT).until { sent.any { it.to == email } }
        return sent.last { it.to == email }
    }

    private fun pendingResetMails(): Int =
        jdbcTemplate.queryForObject(
            """
            select count(*) from event_publication
            where event_type = 'com.ogu.member.PasswordResetRequested' and completion_date is null
            """.trimIndent(),
            Int::class.java,
        )!!

    private fun request(
        email: String,
        clientIp: String = uniqueIp(),
    ): ResultActions =
        mockMvc.perform(
            post(PATH)
                .header(BffClientIpResolver.BFF_KEY_HEADER, authProperties.bffKey)
                .header(BffClientIpResolver.CLIENT_IP_HEADER, clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("email" to email))),
        )

    private fun confirm(
        token: String,
        password: String,
    ): ResultActions =
        mockMvc.perform(
            post("$PATH/confirm")
                .header(BffClientIpResolver.BFF_KEY_HEADER, authProperties.bffKey)
                .header(BffClientIpResolver.CLIENT_IP_HEADER, FIXED_IP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("token" to token, "password" to password))),
        )

    /** 로그인 제한을 재려고 같은 곳(재설정을 한 곳)에서 로그인한다. */
    private fun loginFrom(
        email: String,
        password: String,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/auth/login")
                .header(BffClientIpResolver.BFF_KEY_HEADER, authProperties.bffKey)
                .header(BffClientIpResolver.CLIENT_IP_HEADER, FIXED_IP)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(mapOf("email" to email, "password" to password))),
        )

    private fun me(member: TestMember) = mockMvc.perform(get("/api/v1/members/me").bearer(member.accessToken))

    private fun uniqueIp(): String {
        val hex = UUID.randomUUID().toString()
        return "2001:db8::${hex.take(4)}:${hex.substring(4, 8)}"
    }

    private companion object {
        const val PATH = "/api/v1/auth/password-reset"
        const val NEW_PASSWORD = "newpass123"
        const val FIXED_IP = "2001:db8::15"
        const val EMAIL_LIMIT = 3
        const val SOURCE_LIMIT = 10
        const val LOGIN_LIMIT = 5
        const val RACERS = 4
        const val WAIT_SECONDS = 10L
        const val NO_CONTENT = 204
        const val BAD_REQUEST = 400
        const val SETTLE_MILLIS = 1_000L
        val AWAIT: Duration = Duration.ofSeconds(10)
    }
}
