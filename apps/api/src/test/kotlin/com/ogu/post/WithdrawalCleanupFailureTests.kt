package com.ogu.post

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.application.WithdrawnContentCleaner
import com.ogu.shared.config.EventPublicationResubmitter
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.WithdrawalFixture
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper

/**
 * 탈퇴 뒤의 정리가 실패해도 탈퇴는 끝나 있고, 다시 보내면 이어서 끝난다(013 US5, research R2, R8). 본문을 비우는
 * 리스너가 한 번 실패하게 만들고, 정기 재전송은 끈 채 [EventPublicationResubmitter.resubmit]을 직접 부른다.
 * 재시작 때 끝나지 않은 발행이 다시 나가는 것도 같은 길(발행 기록)이다.
 */
@SpringBootTest(
    properties = [
        "ogu.events.resubmit.enabled=false",
        "ogu.events.resubmit.older-than=0s",
    ],
)
@Import(TestcontainersConfiguration::class)
// 스파이와 설정이 이 클래스만의 것이라 컨텍스트를 나눠 쓰지 않는다. 끝나면 닫아 컨테이너와 메모리를 돌려준다
@DirtiesContext
class WithdrawalCleanupFailureTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var resubmitter: EventPublicationResubmitter

    @MockitoSpyBean
    lateinit var cleaner: WithdrawnContentCleaner

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var safety: SafetyFixture
    private lateinit var withdrawal: WithdrawalFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        withdrawal = WithdrawalFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US5-AC1 US5-AC2 US5-AC3 US5-AC6 정리가 실패해도 탈퇴는 끝나고 다시 보내면 남은 정리가 끝난다`() {
        val leaver = members.onboarded()
        val other = members.onboarded()
        val operator = members.onboarded()
        safety.grantOperator(operator)
        val postId = loop.postWithoutMonster(leaver)
        doThrow(IllegalStateException("일시 장애")).doCallRealMethod().`when`(cleaner).clean(anyLong())

        withdrawal.withdraw(leaver).andExpect(status().isNoContent)

        // US5-AC1: 탈퇴는 끝났다. 글은 보이지 않고 다시 로그인한 것처럼 쓸 수 없다
        assertThat(safety.feedPostIds(other)).doesNotContain(postId)
        mockMvc.perform(get("/api/v1/members/me").bearer(leaver.accessToken)).andExpect(status().isUnauthorized)
        // 알림과 리포트의 정리는 끝나고 본문을 비우는 것만 남는다
        await().atMost(WithdrawalFixture.AWAIT_LIMIT).until { withdrawal.pendingCleanups(leaver.id) == 1 }
        assertThat(withdrawal.contentsLeft(leaver.id)).isEqualTo(1)
        // US5-AC6: 운영자가 끝나지 않은 탈퇴를 본다
        assertThat(pendingOf(operator)).isGreaterThanOrEqualTo(1)

        // US5-AC2, AC3: 끝나지 않은 발행이 다시 나가면 실패한 부분만 다시 한다
        resubmitter.resubmit()

        withdrawal.awaitCleaned(leaver.id)
        assertThat(withdrawal.contentsLeft(leaver.id)).isZero()
    }

    private fun pendingOf(operator: com.ogu.support.TestMember): Int {
        val body =
            mockMvc
                .perform(get("/api/v1/operator/withdrawals/cleanup").bearer(operator.accessToken))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        val data = jsonMapper.readTree(body).get("data")
        assertThat(data.get("oldestWithdrawnAt").isNull).isFalse()
        return data.get("pending").asInt()
    }
}
