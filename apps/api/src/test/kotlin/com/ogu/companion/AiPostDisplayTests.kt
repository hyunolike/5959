package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.monster.MonsterApi
import com.ogu.post.CommentTone
import com.ogu.post.CompanionPostApi
import com.ogu.support.CompanionFixture
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import java.time.Duration

/**
 * AI 친구의 글은 어디서나 AI의 글로 보인다(012 US4, research R17). 글쓰기를 켜지 않은 기본 컨텍스트에서, AI 친구의 글을
 * 파사드로 바로 저장해 본다. 글의 작성자가 나가는 응답마다 `ai`가 실리고 직군과 경력은 null이다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AiPostDisplayTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var companionPostApi: CompanionPostApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var companions: CompanionFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        companions = CompanionFixture(jdbcTemplate)
    }

    @Test
    fun `US4-AC1 AI 친구의 글은 피드에서 author의 ai가 true이고 직군과 경력이 null이다`() {
        val viewer = members.onboarded()
        val aiPost = aiPost()
        val humanPost = loop.postWithoutMonster(members.onboarded())

        val items = feed(viewer, "size=50").get("items").toList()

        val ai = items.single { it.get("postId").asLong() == aiPost }.get("author")
        assertThat(ai.get("ai").asBoolean()).isTrue()
        assertThat(ai.get("nickname").asString()).isEqualTo("다정한 오구")
        // 계약의 Author: 두 필드는 빠지지 않고 null로 온다
        assertThat(ai.has("jobRole")).isTrue()
        assertThat(ai.get("jobRole").isNull).isTrue()
        assertThat(ai.has("careerYear")).isTrue()
        assertThat(ai.get("careerYear").isNull).isTrue()
        val human = items.single { it.get("postId").asLong() == humanPost }.get("author")
        assertThat(human.get("ai").asBoolean()).isFalse()
        assertThat(human.get("jobRole").isNull).isFalse()
    }

    @Test
    fun `US4-AC2 글 상세도 author의 ai가 true이고 직군과 경력이 null이다`() {
        val viewer = members.onboarded()
        val aiPost = aiPost()

        val author = loop.data(loop.detail(viewer, aiPost).andExpect(status().isOk)).get("author")

        assertThat(author.get("ai").asBoolean()).isTrue()
        assertThat(author.get("jobRole").isNull).isTrue()
        assertThat(author.get("careerYear").isNull).isTrue()
        assertThat(author.get("nickname").asString()).isEqualTo("다정한 오구")
    }

    @Test
    fun `US4-AC3 둘러보기에 AI 친구의 글이 ai와 함께 나오고 닉네임과 회원 번호는 없다`() {
        val aiPost = aiPost()
        val humanPost = loop.postWithoutMonster(members.onboarded())
        // 둘러보기는 위험 판정이 없음으로 끝난 글만 내보낸다
        listOf(aiPost, humanPost).forEach { postId -> companions.awaitRisk(postId) { it == "DONE" } }

        val response = mockMvc.perform(get("/api/v1/public/feed")).andExpect(status().isOk)
        val items = loop.data(response).get("items").toList()

        val ai = items.single { it.get("postId").asLong() == aiPost }
        assertThat(ai.get("ai").asBoolean()).isTrue()
        assertThat(ai.get("jobRole").isNull).isTrue()
        assertThat(ai.get("careerYear").isNull).isTrue()
        assertThat(ai.has("nickname")).isFalse()
        assertThat(ai.has("author")).isFalse()
        assertThat(ai.has("authorId")).isFalse()
        assertThat(ai.toString()).doesNotContain("오구")
        val human = items.single { it.get("postId").asLong() == humanPost }
        assertThat(human.get("ai").asBoolean()).isFalse()
        assertThat(human.get("jobRole").isNull).isFalse()
    }

    @Test
    fun `US4-AC4 내가 공감한 글 목록에서도 AI 친구의 글은 ai가 true다`() {
        val viewer = members.onboarded()
        val aiPost = aiPost()
        loop.likePost(viewer, aiPost).andExpect(status().isOk)

        val response = mockMvc.perform(get("/api/v1/members/me/liked-posts").bearer(viewer.accessToken))
        val item = loop.data(response.andExpect(status().isOk)).get("items").single()

        assertThat(item.get("postId").asLong()).isEqualTo(aiPost)
        assertThat(item.get("author").get("ai").asBoolean()).isTrue()
        assertThat(item.get("author").get("jobRole").isNull).isTrue()
    }

    @Test
    fun `US4-AC5 직군이나 경력으로 거른 피드에는 AI 친구의 글이 나오지 않는다`() {
        val viewer = members.onboarded()
        val aiPost = aiPost()

        val all = postIds(viewer, "size=50")
        val byJob = JOB_ROLES.flatMap { role -> postIds(viewer, "size=50&jobRole=$role") }
        val byCareer = CAREER_YEARS.flatMap { year -> postIds(viewer, "size=50&careerYear=$year") }

        assertThat(all).contains(aiPost)
        assertThat(byJob).doesNotContain(aiPost)
        assertThat(byCareer).doesNotContain(aiPost)
    }

    @Test
    fun `US6-AC5 AI 친구의 글에 몬스터가 생겨도 글쓴 AI 친구에게는 알림이 만들어지지 않는다`() {
        val writer = companions.memberIds.first()
        val postId = companionPostApi.write(writer, "[불안:낮음] 내일 발표가 걱정이에요. 준비해도 준비한 것 같지 않아요.", TONE)
        loop.awaitMonster(postId)
        loop.likePost(members.onboarded(), postId).andExpect(status().isOk)

        // 비동기 알림 처리가 끝날 시간을 준 뒤에도 0이다
        await().pollDelay(SETTLE).atMost(CompanionFixture.AWAIT_LIMIT).until { true }
        assertThat(companions.notificationCount(writer)).isZero()
    }

    private fun aiPost(): Long = companionPostApi.write(companions.memberIds.first(), AI_POST, TONE)

    private fun postIds(
        viewer: TestMember,
        query: String,
    ): List<Long> = feed(viewer, query).get("items").toList().map { it.get("postId").asLong() }

    private fun feed(
        viewer: TestMember,
        query: String,
    ): JsonNode {
        val response = mockMvc.perform(get("/api/v1/feed?$query").bearer(viewer.accessToken))
        return loop.data(response.andExpect(status().isOk))
    }

    private companion object {
        val TONE = CommentTone.COMFORT_ME
        const val AI_POST = "[실패] AI 친구가 지어 쓴 글이에요. 회의가 길었어요."
        val JOB_ROLES = JobRole.entries.map { it.name }
        val CAREER_YEARS = CareerYear.entries.map { it.name }
        val SETTLE: Duration = Duration.ofSeconds(1)
    }
}
