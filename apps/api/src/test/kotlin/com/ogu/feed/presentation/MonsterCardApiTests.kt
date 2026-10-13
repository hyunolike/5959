package com.ogu.feed.presentation

import com.ogu.TestcontainersConfiguration
import com.ogu.feed.application.MonsterCardQuery
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.QueryCounter
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 몬스터 도감 조회(010 US1, US2, US4, research R2~R6). 도감은 그 회원의 것만 보이므로 테스트마다 새 회원을 만든다.
 * 처치는 실제 공감과 댓글로 한다. 회원 한 명이 줄이는 HP는 4(공감 1, 첫 댓글 3)라, 남은 HP를 미리 낮춰 두고 친다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MonsterCardApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var monsterCardQuery: MonsterCardQuery

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US1-AC1 내 글의 몬스터가 처치되면 MINE 카드가 한 장 있다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val postId = post(author, "불안")
        defeat(postId, helper)

        val page = cards(author)

        assertThat(page.get("items")).hasSize(1)
        val card = page.get("items").get(0)
        assertThat(card.get("role").asString()).isEqualTo("MINE")
        assertThat(card.get("cardNo").asLong()).isEqualTo(monsterId(postId))
        assertThat(card.get("emotion").asString()).isEqualTo("ANXIETY")
        assertThat(card.get("postId").asLong()).isEqualTo(postId)
        assertThat(card.get("defeatedAt").asString()).isNotBlank()
    }

    @Test
    fun `US1-AC2 함께 물리친 몬스터는 TOGETHER 카드다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val postId = post(author, "짜증")
        defeat(postId, helper)

        val card = cards(helper).get("items").single()

        assertThat(card.get("role").asString()).isEqualTo("TOGETHER")
        assertThat(card.get("emotion").asString()).isEqualTo("IRRITATION")
        assertThat(card.get("postId").asLong()).isEqualTo(postId)
    }

    @Test
    fun `US1-AC3 살아 있는 몬스터는 카드가 아니다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val postId = post(author, "불안")
        coreLoop.likePost(helper, postId).andExpect(status().isOk)

        assertThat(cards(author).get("items")).isEmpty()
        assertThat(cards(helper).get("items")).isEmpty()
    }

    @Test
    fun `US1-AC4 처치된 뒤에만 반응한 회원에게는 카드가 없다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val late = members.onboarded()
        val postId = post(author, "불안")
        defeat(postId, helper)

        coreLoop.likePost(late, postId).andExpect(status().isOk)
        coreLoop.comment(late, postId)

        assertThat(cards(late).get("items")).isEmpty()
        assertThat(cards(late).get("summary").get("total").asInt()).isZero()
    }

    @Test
    fun `US1-AC5 최근에 물리친 것부터이고 커서로 이어서 본다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val posts = listOf("불안", "짜증", "외로움").map { post(author, it) }
        posts.forEach { defeat(it, helper) }

        val first = cards(helper, size = 2)
        val second = cards(helper, size = 2, cursor = first.get("nextCursor").asString())

        assertThat(first.get("items").toList().map { it.get("postId").asLong() }).containsExactly(posts[2], posts[1])
        assertThat(second.get("items").toList().map { it.get("postId").asLong() }).containsExactly(posts[0])
        assertThat(second.get("nextCursor").isNull).isTrue()
        // 요약은 쪽과 상관없이 전체다
        assertThat(first.get("summary").get("total").asInt()).isEqualTo(3)
        assertThat(second.get("summary").get("total").asInt()).isEqualTo(3)
    }

    @Test
    fun `US1-AC6 카드가 없으면 빈 목록이고 요약은 모두 0이다`() {
        val member = members.onboarded()

        val page = cards(member)

        assertThat(page.get("items")).isEmpty()
        assertThat(page.get("nextCursor").isNull).isTrue()
        assertThat(page.get("summary").get("total").asInt()).isZero()
        assertThat(
            page
                .get("summary")
                .get("byEmotion")
                .toList()
                .map { it.get("emotion").asString() },
        ).containsExactly("ANXIETY", "LETHARGY", "LONELINESS", "SELF_DEPRECATION", "IRRITATION")
        assertThat(
            page
                .get("summary")
                .get("byEmotion")
                .toList()
                .map { it.get("count").asInt() },
        ).containsOnly(0)
    }

    @Test
    fun `US1-AC7 지운 글과 숨긴 글의 몬스터는 카드가 아니고 숨김이 풀리면 돌아온다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val deleted = post(author, "불안")
        val hidden = post(author, "짜증")
        defeat(deleted, helper)
        defeat(hidden, helper)

        coreLoop.deletePost(deleted)
        jdbcTemplate.update("update posts set hidden_at = now(), hidden_reason = 'OPERATOR' where id = ?", hidden)

        assertThat(cards(author).get("items")).isEmpty()
        assertThat(cards(helper).get("items")).isEmpty()
        assertThat(cards(helper).get("summary").get("total").asInt()).isZero()

        jdbcTemplate.update("update posts set hidden_at = null, hidden_reason = null where id = ?", hidden)

        assertThat(cards(author).get("items").toList().map { it.get("postId").asLong() }).containsExactly(hidden)
        assertThat(cards(helper).get("items").toList().map { it.get("postId").asLong() }).containsExactly(hidden)
    }

    @Test
    fun `US1-AC8 로그인하지 않으면 401이고 온보딩 전이면 403이다`() {
        mockMvc.perform(get(CARDS)).andExpect(status().isUnauthorized)
        mockMvc
            .perform(get(CARDS).bearer(members.signedUp().accessToken))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    @Test
    fun `US1-AC9 공감도 하고 댓글도 단 몬스터의 카드는 한 장이다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val postId = post(author, "불안")
        defeat(postId, helper)

        assertThat(coreLoop.hpLogs(postId).filter { it.memberId == helper.id }).hasSize(2)
        assertThat(cards(helper).get("items")).hasSize(1)
        assertThat(cards(helper).get("summary").get("total").asInt()).isEqualTo(1)
    }

    @Test
    fun `US2-AC2 세기 낮음, 보통, 높음은 COMMON, RARE, LEGENDARY다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val posts = listOf("낮음", "보통", "높음").map { post(author, "불안", intensity = it) }
        posts.forEach { defeat(it, helper) }

        val grades = cards(author).get("items").associate { it.get("postId").asLong() to it.get("grade").asString() }

        assertThat(grades).isEqualTo(mapOf(posts[0] to "COMMON", posts[1] to "RARE", posts[2] to "LEGENDARY"))
    }

    @Test
    fun `US2-AC3 TOGETHER 카드는 내가 줄인 HP가 있고 helperCount가 null이다`() {
        val author = members.onboarded()
        val first = members.onboarded()
        val second = members.onboarded()
        val postId = post(author, "불안")
        // 첫 회원이 4를, 둘째 회원이 남은 2를 줄인다
        weaken(postId, hp = 6)
        attack(first, postId)
        attack(second, postId)

        val mine = cards(first).get("items").single()
        val theirs = cards(second).get("items").single()

        assertThat(mine.get("myDamage").asInt()).isEqualTo(4)
        assertThat(theirs.get("myDamage").asInt()).isEqualTo(2)
        assertThat(mine.get("helperCount").isNull).isTrue()
    }

    @Test
    fun `US2-AC4 MINE 카드는 함께한 회원 수가 있고 myDamage가 0이다`() {
        val author = members.onboarded()
        val first = members.onboarded()
        val second = members.onboarded()
        val late = members.onboarded()
        val postId = post(author, "불안")
        weaken(postId, hp = 8)
        attack(first, postId)
        attack(second, postId)
        // 처치된 뒤의 응원은 함께한 회원으로 세지 않는다
        coreLoop.likePost(late, postId).andExpect(status().isOk)

        val card = cards(author).get("items").single()

        assertThat(card.get("helperCount").asInt()).isEqualTo(2)
        assertThat(card.get("myDamage").asInt()).isZero()
    }

    @Test
    fun `US2-AC5 응답 원문에 다른 회원의 글의 본문과 닉네임이 없다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val postId = coreLoop.createPost(author, "[불안:낮음] $SECRET_BODY")
        coreLoop.awaitMonster(postId)
        defeat(postId, helper)
        val nickname =
            jdbcTemplate.queryForObject("select nickname from member where id = ?", String::class.java, author.id)!!

        val raw =
            request(helper)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString

        assertThat(raw).contains("\"postId\":$postId")
        assertThat(raw).doesNotContain(SECRET_BODY).doesNotContain(nickname)
        assertThat(raw).doesNotContain("content").doesNotContain("nickname").doesNotContain("author")
    }

    @Test
    fun `US2-AC7 다시 읽어도 카드 번호와 등급이 같다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        val other = members.onboarded()
        val postId = post(author, "불안", intensity = "보통")
        defeat(postId, helper)
        val before = cards(helper).get("items").single()

        // 다른 몬스터가 더 처치돼도 앞의 카드는 그대로다
        defeat(post(other, "짜증"), helper)
        val after = cards(helper).get("items").single { it.get("postId").asLong() == postId }

        assertThat(after.get("cardNo").asLong()).isEqualTo(before.get("cardNo").asLong())
        assertThat(after.get("grade").asString()).isEqualTo("RARE")
        assertThat(after.get("defeatedAt").asString()).isEqualTo(before.get("defeatedAt").asString())
    }

    @Test
    fun `US4-AC1 요약은 모은 수와 다섯 감정의 장수다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        listOf("불안", "불안", "짜증").forEach { defeat(post(author, it), helper) }

        val summary = cards(helper).get("summary")

        assertThat(summary.get("total").asInt()).isEqualTo(3)
        assertThat(summary.get("byEmotion").associate { it.get("emotion").asString() to it.get("count").asInt() })
            .isEqualTo(
                mapOf("ANXIETY" to 2, "LETHARGY" to 0, "LONELINESS" to 0, "SELF_DEPRECATION" to 0, "IRRITATION" to 1),
            )
    }

    @Test
    fun `US4-AC3 감정으로 거르면 그 감정의 카드만 오고 요약은 그대로다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        listOf("불안", "불안", "짜증").forEach { defeat(post(author, it), helper) }

        val page = cards(helper, emotion = "IRRITATION")

        assertThat(page.get("items").toList().map { it.get("emotion").asString() }).containsExactly("IRRITATION")
        assertThat(page.get("summary").get("total").asInt()).isEqualTo(3)
        assertThat(cards(helper, emotion = "LETHARGY").get("items")).isEmpty()
    }

    @Test
    fun `size가 범위 밖이거나 커서나 감정이 틀리면 400 INVALID_REQUEST`() {
        val member = members.onboarded()

        for (params in listOf("size" to "0", "size" to "51", "cursor" to "틀린커서", "emotion" to "JOY")) {
            mockMvc
                .perform(get(CARDS).bearer(member.accessToken).param(params.first, params.second))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `SC-004 쿼리는 카드 수와 상관없이 세 개다`() {
        val author = members.onboarded()
        val helper = members.onboarded()
        defeat(post(author, "불안"), helper)
        val (_, one) = QueryCounter.count { monsterCardQuery.page(helper.id, null, 20, null) }
        listOf("짜증", "외로움", "불안").forEach { defeat(post(author, it), helper) }

        val (page, many) = QueryCounter.count { monsterCardQuery.page(helper.id, null, 20, null) }

        assertThat(page.items).hasSize(4)
        assertThat(one).isEqualTo(3)
        assertThat(many).isEqualTo(3)
    }

    private fun post(
        author: TestMember,
        emotion: String,
        intensity: String = "낮음",
    ): Long {
        val postId = coreLoop.createPost(author, "[$emotion:$intensity] 카드가 될 글")
        coreLoop.awaitMonster(postId)
        return postId
    }

    /** 남은 HP를 4로 낮춘 뒤 [helper]의 공감과 댓글로 처치한다. [helper]가 줄인 HP는 4다. */
    private fun defeat(
        postId: Long,
        helper: TestMember,
    ) {
        weaken(postId, hp = 4)
        attack(helper, postId)
        assertThat(coreLoop.monster(postId)!!.hp).isZero()
    }

    private fun weaken(
        postId: Long,
        hp: Int,
    ) {
        jdbcTemplate.update("update monsters set hp = ? where post_id = ?", hp, postId)
    }

    private fun attack(
        member: TestMember,
        postId: Long,
    ) {
        coreLoop.likePost(member, postId).andExpect(status().isOk)
        coreLoop.comment(member, postId)
    }

    private fun monsterId(postId: Long): Long =
        jdbcTemplate.queryForObject("select id from monsters where post_id = ?", Long::class.java, postId)!!

    private fun request(
        member: TestMember,
        size: Int? = null,
        cursor: String? = null,
        emotion: String? = null,
    ): ResultActions {
        val request = get(CARDS).bearer(member.accessToken)
        size?.let { request.param("size", it.toString()) }
        cursor?.let { request.param("cursor", it) }
        emotion?.let { request.param("emotion", it) }
        return mockMvc.perform(request)
    }

    private fun cards(
        member: TestMember,
        size: Int? = null,
        cursor: String? = null,
        emotion: String? = null,
    ): JsonNode {
        val body =
            request(member, size, cursor, emotion)
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return jsonMapper.readTree(body).get("data")
    }

    companion object {
        private const val CARDS = "/api/v1/members/me/monster-cards"
        private const val SECRET_BODY = "남에게보이면안되는본문"
    }
}
