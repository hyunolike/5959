package com.ogu.companion

import com.ogu.companion.application.ReactionRunner
import com.ogu.monster.MonsterApi
import com.ogu.support.CompanionFixture
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MutableClock
import com.ogu.support.SafetyFixture
import com.ogu.support.ScriptedCompanionCommentWriter
import com.ogu.support.ScriptedReactionCountPicker
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * AI 친구의 반응을 시계를 움직이며 확인하는 테스트의 바탕(009 research R14). 주기 작업을 끄고 실행기를 글마다 직접 부른다.
 * 컨텍스트들이 DB를 함께 써서, 차례가 된 글을 모두 맡는 `runDue`를 부르면 다른 테스트의 글에도 댓글이 달린다.
 * 실제 `post`, `monster`, `notification`, `safety`가 함께 돈다.
 */
abstract class CompanionTestBase {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var clock: MutableClock

    @Autowired
    lateinit var runner: ReactionRunner

    @Autowired
    lateinit var writer: ScriptedCompanionCommentWriter

    @Autowired
    lateinit var countPicker: ScriptedReactionCountPicker

    protected val jsonMapper: JsonMapper = JsonMapper.builder().build()
    protected lateinit var mockMvc: MockMvc
    protected lateinit var members: MemberFixture
    protected lateinit var loop: CoreLoopFixture
    protected lateinit var safety: SafetyFixture
    protected lateinit var companions: CompanionFixture

    @BeforeEach
    fun setUpFixtures() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        companions = CompanionFixture(jdbcTemplate, clock)
    }

    @AfterEach
    fun resetCount() {
        countPicker.next = 1
    }

    /**
     * 글을 쓰고 반응 기록과 위험 분류가 준비될 때까지 기다린다. `[실패]`로 시작하면 몬스터가 생기지 않는다. [count]는 이 글에
     * 반응할 AI 친구의 수다(012). 반응 기록이 생길 때 정해진다.
     */
    protected fun postOf(
        author: TestMember,
        content: String = "[실패] 회의가 세 시간째 끝나질 않아요",
        tone: String = "COMFORT_ME",
        ready: Boolean = true,
        count: Int = 1,
    ): Long {
        countPicker.next = count
        val body = jsonMapper.writeValueAsString(mapOf("content" to content, "commentTone" to tone))
        val request = post("/api/v1/posts").bearer(author.accessToken).contentType(MediaType.APPLICATION_JSON)
        val response = mockMvc.perform(request.content(body)).andExpect(status().isCreated)
        val postId = loop.data(response).get("postId").asLong()
        if (ready) companions.awaitReady(postId) else companions.awaitEnrolled(postId)
        countPicker.next = 1
        return postId
    }

    /** 뒤따르는 친구의 차례가 올 만큼 시간을 보낸 뒤 이 글을 한 번 맡는다. */
    protected fun followUp(postId: Long): Boolean = reactAfter(postId, CompanionFixture.FOLLOW_UP)

    protected fun countsOf(postId: Long): Pair<Int, Int> {
        val row = companions.reaction(postId)!!
        return (row["target_count"] as Number).toInt() to (row["done_count"] as Number).toInt()
    }

    /** 기다리는 시간을 보낸 뒤 이 글을 한 번 맡는다. 맡았으면 true다. */
    protected fun reactAfter(
        postId: Long,
        wait: Duration = CompanionFixture.WAIT,
    ): Boolean {
        companions.pass(wait)
        return runner.attempt(postId)
    }

    protected fun statusOf(postId: Long): String = companions.reaction(postId)!!["status"] as String

    protected fun skipReasonOf(postId: Long): String? = companions.reaction(postId)!!["skip_reason"] as String?
}
