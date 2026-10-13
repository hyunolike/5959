package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.SafetyFixture.Companion.CONCERN_TEXT
import com.ogu.support.SafetyFixture.Companion.CRISIS_TEXT
import com.ogu.support.SafetyFixture.Companion.SAFE_TEXT
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * 글의 위험 판정이 끝났는지와 글자의 키워드 단계를 다른 모듈에 알려 주는 파사드(009 research R7). 가짜 분류기의 표지
 * (`[우려]`, `[위험분류실패]`)로 상태를 만든다. 늘 실패하는 표지를 쓰므로 재시도 주기 작업이 돌아도 기다리는 상태로 남는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RiskApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var riskApi: RiskApi

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US3-AC3 AI 분류가 끝나지 않은 글은 settled가 아니다`() {
        val postId = loop.createPost(members.onboarded(), "[실패] [위험분류실패] $SAFE_TEXT")
        awaitAssessment(postId) { (it["attempts"] as Int) >= 1 }

        val risk = riskApi.postRisk(postId)

        assertThat(risk.settled).isFalse()
        assertThat(risk.level).isEqualTo(RiskLevel.NONE)
    }

    @Test
    fun `US3-AC3 분류가 끝나면 settled이고 그 단계다`() {
        val author = members.onboarded()
        val safe = loop.createPost(author, "[실패] $SAFE_TEXT")
        val concern = loop.createPost(author, "[실패] [우려] $SAFE_TEXT")
        val byKeyword = loop.createPost(author, "[실패] $CONCERN_TEXT")
        listOf(safe, concern, byKeyword).forEach { postId -> awaitAssessment(postId) { it["status"] == "DONE" } }

        assertThat(riskApi.postRisk(safe)).isEqualTo(PostRisk(settled = true, level = RiskLevel.NONE))
        assertThat(riskApi.postRisk(concern)).isEqualTo(PostRisk(settled = true, level = RiskLevel.CONCERN))
        assertThat(riskApi.postRisk(byKeyword)).isEqualTo(PostRisk(settled = true, level = RiskLevel.CONCERN))
    }

    @Test
    fun `US3-AC3 글을 고치면 새 판정이 끝날 때까지 다시 settled가 아니다`() {
        val author = members.onboarded()
        val postId = loop.createPost(author, "[실패] $SAFE_TEXT")
        awaitAssessment(postId) { it["status"] == "DONE" }
        assertThat(riskApi.postRisk(postId).settled).isTrue()

        loop.updatePost(author, postId, mapOf("content" to "[실패] [위험분류실패] 고친 글")).andExpect(status().isNoContent)
        awaitAssessment(postId) { it["status"] == "PENDING" && (it["attempts"] as Int) >= 1 }

        assertThat(riskApi.postRisk(postId).settled).isFalse()
    }

    @Test
    fun `US3-AC3 판정 기록이 없는 글은 끝난 것으로 보고 글에 적힌 단계를 따른다`() {
        val postId = safety.insertPost(members.onboarded(), SAFE_TEXT)

        assertThat(riskApi.postRisk(postId)).isEqualTo(PostRisk(settled = true, level = RiskLevel.NONE))
        jdbcTemplate.update("update posts set risk_level = 'CONCERN' where id = ?", postId)
        assertThat(riskApi.postRisk(postId)).isEqualTo(PostRisk(settled = true, level = RiskLevel.CONCERN))
        // 없는 글은 물을 것이 없다. 글이 사라졌는지는 post가 답한다
        assertThat(riskApi.postRisk(Long.MAX_VALUE)).isEqualTo(PostRisk(settled = true, level = RiskLevel.NONE))
    }

    @Test
    fun `US3-AC4 위기나 우려 낱말이 든 글자는 keywordLevel이 그 단계다`() {
        assertThat(riskApi.keywordLevel(CRISIS_TEXT)).isEqualTo(RiskLevel.CRISIS)
        assertThat(riskApi.keywordLevel(CONCERN_TEXT)).isEqualTo(RiskLevel.CONCERN)
        assertThat(riskApi.keywordLevel(SAFE_TEXT)).isEqualTo(RiskLevel.NONE)
        // 가짜 분류기의 표지는 키워드가 아니다
        assertThat(riskApi.keywordLevel("[위기] $SAFE_TEXT")).isEqualTo(RiskLevel.NONE)
    }

    private fun awaitAssessment(
        postId: Long,
        until: (Map<String, Any?>) -> Boolean,
    ) {
        await().atMost(SafetyFixture.AWAIT_LIMIT).pollInterval(SafetyFixture.POLL).until {
            jdbcTemplate
                .queryForList(
                    "select * from risk_assessment where target_type = 'POST' and target_id = ? " +
                        "order by id desc limit 1",
                    postId,
                ).firstOrNull()
                ?.let(until) ?: false
        }
    }
}
