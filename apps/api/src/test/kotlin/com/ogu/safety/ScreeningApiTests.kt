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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/** T014: 저장과 같은 트랜잭션의 키워드 판정, 숨김, 작성자에게만 가는 safety(005 US1, research R2, R7). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScreeningApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US1-AC1 위기 표현이 든 글을 쓰면 201이고 상세의 safety가 CRISIS, hidden이다`() {
        val author = members.onboarded()

        val postId = coreLoop.createPost(author, CRISIS_TEXT)

        assertThat(safety.postState(postId)).containsEntry("hidden", true).containsEntry("hidden_reason", "RISK")
        coreLoop
            .detail(author, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.content").value(CRISIS_TEXT))
            .andExpect(jsonPath("$.data.safety.level").value("CRISIS"))
            .andExpect(jsonPath("$.data.safety.hidden").value(true))
            .andExpect(jsonPath("$.data.safety.reviewRequested").value(false))
    }

    @Test
    fun `US1-AC2 글 작성 응답 직후 다른 회원의 피드와 상세에 없다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val visible = coreLoop.createPost(author, SAFE_TEXT)

        val hidden = coreLoop.createPost(author, CRISIS_TEXT)

        assertThat(safety.feedPostIds(other)).contains(visible).doesNotContain(hidden)
        coreLoop
            .detail(other, hidden)
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
    }

    @Test
    fun `US1-AC4 우려 표현이 든 글은 숨기지 않고 작성자의 safety만 CONCERN이다`() {
        val author = members.onboarded()
        val other = members.onboarded()

        val postId = coreLoop.createPost(author, CONCERN_TEXT)

        assertThat(safety.postState(postId)).containsEntry("hidden", false).containsEntry("risk_level", "CONCERN")
        assertThat(safety.feedPostIds(other)).contains(postId)
        coreLoop
            .detail(author, postId)
            .andExpect(jsonPath("$.data.safety.level").value("CONCERN"))
            .andExpect(jsonPath("$.data.safety.hidden").value(false))
        // 다른 회원의 응답에는 safety 필드가 없다
        coreLoop
            .detail(other, postId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.safety").doesNotExist())
    }

    @Test
    fun `위험 표현이 없는 글의 작성자에게는 safety가 NONE으로 실리고 다른 회원에게는 없다`() {
        val author = members.onboarded()
        val other = members.onboarded()

        val postId = coreLoop.createPost(author, SAFE_TEXT)

        coreLoop
            .detail(author, postId)
            .andExpect(jsonPath("$.data.safety.level").value("NONE"))
            .andExpect(jsonPath("$.data.safety.hidden").value(false))
        coreLoop.detail(other, postId).andExpect(jsonPath("$.data.safety").doesNotExist())
    }

    @Test
    fun `US1-AC5 위기 표현이 든 댓글의 작성 응답에 safety가 실리고 다른 회원에게는 자리만 보인다`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = coreLoop.createPost(author, SAFE_TEXT)

        val response = coreLoop.writeComment(commenter, postId, CRISIS_TEXT)

        response
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.content").value(CRISIS_TEXT))
            .andExpect(jsonPath("$.data.hidden").value(true))
            .andExpect(jsonPath("$.data.safety.level").value("CRISIS"))
            .andExpect(jsonPath("$.data.safety.hidden").value(true))
        val commentId = coreLoop.data(response).get("commentId").asLong()
        assertThat(safety.commentState(commentId)).containsEntry("hidden", true)

        coreLoop
            .comments(author, postId)
            .andExpect(jsonPath("$.data.items[0].commentId").value(commentId))
            .andExpect(jsonPath("$.data.items[0].hidden").value(true))
            .andExpect(jsonPath("$.data.items[0].content").value(null))
            .andExpect(jsonPath("$.data.items[0].author").value(null))
            .andExpect(jsonPath("$.data.items[0].safety").doesNotExist())
        coreLoop
            .comments(commenter, postId)
            .andExpect(jsonPath("$.data.items[0].content").value(CRISIS_TEXT))
            .andExpect(jsonPath("$.data.items[0].author.id").value(commenter.id))
            .andExpect(jsonPath("$.data.items[0].safety.level").value("CRISIS"))
    }

    @Test
    fun `US2-AC5 고쳐서 위기 표현을 지워도 숨김은 그대로이고, 멀쩡한 글을 고쳐 위기 표현을 넣으면 숨긴다`() {
        val author = members.onboarded()
        val other = members.onboarded()
        val hidden = coreLoop.createPost(author, CRISIS_TEXT)
        val visible = coreLoop.createPost(author, SAFE_TEXT)

        coreLoop.updatePost(author, hidden, mapOf("content" to SAFE_TEXT)).andExpect(status().isNoContent)
        coreLoop.updatePost(author, visible, mapOf("content" to CRISIS_TEXT)).andExpect(status().isNoContent)

        // 판정은 지금 내용 기준이지만 숨김은 운영자가 풀기 전까지 남는다
        assertThat(safety.postState(hidden)).containsEntry("hidden", true).containsEntry("risk_level", "NONE")
        assertThat(safety.postState(visible)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
        assertThat(safety.feedPostIds(other)).doesNotContain(hidden, visible)
    }

    @Test
    fun `말투만 고치면 다시 판정하지 않는다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, SAFE_TEXT)
        val before = assessmentCount(postId)

        coreLoop.updatePost(author, postId, mapOf("commentTone" to "WARM_ADVICE")).andExpect(status().isNoContent)

        assertThat(assessmentCount(postId)).isEqualTo(before)
    }

    @Test
    fun `판정 기록에는 단계만 남고 본문이나 걸린 표현은 저장하지 않는다`() {
        val author = members.onboarded()
        val postId = coreLoop.createPost(author, CRISIS_TEXT)

        val row =
            jdbcTemplate.queryForMap(
                "select * from risk_assessment where target_type = 'POST' and target_id = ? order by id desc limit 1",
                postId,
            )

        assertThat(row["keyword_level"]).isEqualTo("CRISIS")
        assertThat(row["level"]).isEqualTo("CRISIS")
        assertThat(row["author_id"]).isEqualTo(author.id)
        assertThat(row.values.filterIsInstance<String>()).noneMatch { it.contains("죽고") }
        assertThat(row.keys).doesNotContain("content", "matched_term")
    }

    @Test
    fun `도움 리소스 API가 보이는 순서대로 셋을 준다`() {
        val member = members.onboarded()

        safety
            .get(member, "/api/v1/safety/support-resources")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].name").value("자살예방상담전화"))
            .andExpect(jsonPath("$.data[0].phone").value("109"))
            .andExpect(jsonPath("$.data[1].phone").value("1577-0199"))
            .andExpect(jsonPath("$.data[2].phone").value("1388"))
            .andExpect(jsonPath("$.data[0].hours").isString)
            .andExpect(jsonPath("$.data[0].description").isString)
    }

    @Test
    fun `도움 리소스는 온보딩 전 회원에게 403, 토큰이 없으면 401`() {
        safety
            .get(members.signedUp(), "/api/v1/safety/support-resources")
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        mockMvc.perform(get("/api/v1/safety/support-resources")).andExpect(status().isUnauthorized)
    }

    private fun assessmentCount(postId: Long): Int =
        jdbcTemplate.queryForObject(
            "select count(*) from risk_assessment where target_type = 'POST' and target_id = ?",
            Int::class.java,
            postId,
        )!!
}
