package com.ogu.recommend

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.RecommendFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
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
import tools.jackson.databind.JsonNode

/**
 * T009, T011: 글 상세 아래의 비슷한 고민(007 US1, US2, US3). 가짜 임베더는 `[주제:이름]`이 같은 글끼리 가깝게 만든다.
 * 테스트마다 다른 주제 이름을 써서 다른 테스트의 글과 섞이지 않는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SimilarPostsApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var moderation: PostModerationApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var http: SafetyFixture
    private lateinit var recommend: RecommendFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        http = SafetyFixture(mockMvc, jdbcTemplate)
        recommend = RecommendFixture(jdbcTemplate)
    }

    @Test
    fun `US1-AC1 가까운 순서로 최대 5개이고 US1-AC4 다른 주제의 글은 없다`() {
        val topic = recommend.topic()
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[실패] $topic 야근이 석 달째예요")
        // 멀기가 클수록 조금 더 멀다. 여섯 개 가운데 가까운 다섯만 나온다
        val near = (6 downTo 1).map { coreLoop.createPost(writer, "[실패] $topic [멀기:$it] 저도 야근해요") }.reversed()
        val unrelated = coreLoop.createPost(writer, "[실패] ${recommend.topic()} 점심 메뉴가 고민이에요")
        recommend.awaitEmbedded(source, unrelated, *near.toLongArray())

        val body = similar(viewer, source)

        assertThat(body.get("basis").asString()).isEqualTo("SIMILAR")
        assertThat(body.get("pending").asBoolean()).isFalse()
        assertThat(ids(body)).containsExactlyElementsOf(near.take(5))
        assertThat(ids(body)).doesNotContain(unrelated, source)
    }

    @Test
    fun `US1-AC3 지금 글과 내 글은 없고 US1-AC6 피드와 같은 항목이다`() {
        val topic = recommend.topic()
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[불안:낮음] $topic 발표가 걱정돼요")
        val mine = coreLoop.createPost(viewer, "[실패] $topic 내가 쓴 다른 글")
        val theirs = coreLoop.createPost(writer, "[불안:낮음] $topic 저도 발표가 무서워요")
        coreLoop.awaitMonster(theirs)
        coreLoop.likePost(viewer, theirs)
        coreLoop.comment(viewer, theirs, "힘내요")
        recommend.awaitEmbedded(source, mine, theirs)

        val body = similar(viewer, source)

        assertThat(ids(body)).containsExactly(theirs)
        val item = body.get("items").single()
        assertThat(item.get("author").get("id").asLong()).isEqualTo(writer.id)
        assertThat(item.get("contentPreview").asString()).contains("저도 발표가 무서워요")
        assertThat(item.get("analysisStatus").asString()).isEqualTo("ANALYZED")
        assertThat(item.get("monster").get("emotion").asString()).isEqualTo("ANXIETY")
        assertThat(item.get("likeCount").asInt()).isEqualTo(1)
        assertThat(item.get("likedByMe").asBoolean()).isTrue()
        assertThat(item.get("commentCount").asInt()).isEqualTo(1)
        // 글쓴이가 자기 글을 열어도 같다. 다른 회원이 열면 그 회원의 글이 빠진다
        assertThat(ids(similar(writer, theirs))).containsExactlyInAnyOrder(source, mine)
    }

    @Test
    fun `US1-AC5 추천의 글도 다른 회원에게는 욕설이 가려진다`() {
        val topic = recommend.topic()
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[실패] $topic 회의가 길었어요")
        val rude = coreLoop.createPost(writer, "[실패] $topic 병신 같은 회의였어요")
        recommend.awaitEmbedded(source, rude)

        val preview =
            similar(viewer, source)
                .get("items")
                .single()
                .get("contentPreview")
                .asString()

        assertThat(preview).contains("** 같은 회의였어요").doesNotContain("병신")
    }

    @Test
    fun `US1-AC8 로그인하지 않으면 401이고 온보딩 전 회원은 403이다`() {
        val source = coreLoop.createPost(members.onboarded(), "[실패] ${recommend.topic()} 글")

        mockMvc.perform(get(path(source))).andExpect(status().isUnauthorized)
        http
            .get(members.signedUp(), path(source))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
    }

    @Test
    fun `US2-AC2 임베딩이 없으면 같은 감정의 최근 글로 대신하고 US2-AC6 내 글, 숨긴 글, 지운 글은 없다`() {
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val other = members.onboarded()
        val source = coreLoop.createPost(viewer, "[불안:낮음] [임베딩실패] 내일 발표가 무서워요")
        val mine = coreLoop.createPost(viewer, "[불안:낮음] [임베딩실패] 내 다른 불안한 글")
        val same = coreLoop.createPost(writer, "[불안:낮음] [임베딩실패] 저도 불안해요")
        val hidden = coreLoop.createPost(writer, "[불안:낮음] [임베딩실패] 숨겨질 글")
        val deleted = coreLoop.createPost(other, "[불안:낮음] [임베딩실패] 지워질 글")
        val anger = coreLoop.createPost(other, "[짜증:낮음] [임베딩실패] 화가 나요")
        listOf(source, mine, same, hidden, deleted, anger).forEach { coreLoop.awaitMonster(it) }
        moderation.hide(ContentType.POST, hidden, "OPERATOR")
        coreLoop.removePost(other, deleted).andExpect(status().isNoContent)

        val body = similar(viewer, source)

        assertThat(body.get("basis").asString()).isEqualTo("SAME_EMOTION")
        // 임베딩을 아직 만들고 있으니 화면이 잠시 뒤 다시 받는다
        assertThat(body.get("pending").asBoolean()).isTrue()
        assertThat(ids(body)).contains(same).doesNotContain(source, mine, hidden, deleted, anger)
        assertThat(ids(body)).hasSizeLessThanOrEqualTo(5).isSortedAccordingTo(reverseOrder())
        emotionsOf(body).forEach { assertThat(it).isEqualTo("ANXIETY") }
    }

    @Test
    fun `US2-AC3 임베딩도 감정 분석 결과도 없으면 NONE과 빈 목록이다`() {
        val viewer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[실패] [임베딩실패] 아무것도 준비되지 않은 글")
        recommend.awaitAttempts(source, 1)

        val body = similar(viewer, source)

        assertThat(body.get("basis").asString()).isEqualTo("NONE")
        assertThat(body.get("items")).isEmpty()
        assertThat(body.get("pending").asBoolean()).isTrue()
    }

    @Test
    fun `가까운 글이 없으면 같은 감정의 글로 넘어가고 pending은 false다`() {
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[외로움:낮음] ${recommend.topic()} 혼자 밥을 먹어요")
        val lonely = coreLoop.createPost(writer, "[외로움:낮음] ${recommend.topic()} 저도 혼자예요")
        coreLoop.awaitMonster(source)
        coreLoop.awaitMonster(lonely)
        recommend.awaitEmbedded(source, lonely)

        val body = similar(viewer, source)

        assertThat(body.get("basis").asString()).isEqualTo("SAME_EMOTION")
        assertThat(body.get("pending").asBoolean()).isFalse()
        assertThat(ids(body)).contains(lonely)
    }

    @Test
    fun `다른 모델로 만든 값은 견주지 않는다`() {
        val topic = recommend.topic()
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[실패] $topic 글")
        val current = coreLoop.createPost(writer, "[실패] $topic 지금 모델의 글")
        val old = coreLoop.createPost(writer, "[실패] $topic 옛 모델의 글")
        recommend.awaitEmbedded(source, current, old)
        jdbcTemplate.update("update post_embedding set model = 'old-model' where post_id = ?", old)

        assertThat(ids(similar(viewer, source))).containsExactly(current)

        // 지금 글의 값이 옛 모델이면 가까운 글을 찾지 않는다
        jdbcTemplate.update("update post_embedding set model = 'old-model' where post_id = ?", source)
        assertThat(similar(viewer, source).get("basis").asString()).isEqualTo("NONE")
    }

    @Test
    fun `US3-AC2 숨긴 글은 다른 회원의 추천에 없고 풀면 다시 나오며 US3-AC1 지운 글은 없다`() {
        val topic = recommend.topic()
        val viewer = members.onboarded()
        val writer = members.onboarded()
        val source = coreLoop.createPost(viewer, "[실패] $topic 글")
        val hidden = coreLoop.createPost(writer, "[실패] $topic [멀기:1] 숨겨질 글")
        val deleted = coreLoop.createPost(writer, "[실패] $topic [멀기:2] 지워질 글")
        val kept = coreLoop.createPost(writer, "[실패] $topic [멀기:3] 남는 글")
        recommend.awaitEmbedded(source, hidden, deleted, kept)
        assertThat(ids(similar(viewer, source))).containsExactly(hidden, deleted, kept)

        moderation.hide(ContentType.POST, hidden, "RISK")
        coreLoop.removePost(writer, deleted).andExpect(status().isNoContent)

        assertThat(ids(similar(viewer, source))).containsExactly(kept)
        // 지운 글의 값은 지워진다
        recommend.awaitGone(deleted)

        moderation.unhide(ContentType.POST, hidden)
        assertThat(ids(similar(viewer, source))).containsExactly(hidden, kept)
    }

    @Test
    fun `US3-AC5 숨겨진 내 글의 추천은 작성자에게 보이고 다른 회원에게 그 글은 404다`() {
        val topic = recommend.topic()
        val author = members.onboarded()
        val other = members.onboarded()
        val mine = coreLoop.createPost(author, "[실패] $topic 숨겨진 내 글")
        val theirs = coreLoop.createPost(other, "[실패] $topic 다른 회원의 글")
        recommend.awaitEmbedded(mine, theirs)
        moderation.hide(ContentType.POST, mine, "RISK")

        assertThat(ids(similar(author, mine))).containsExactly(theirs)
        http
            .get(other, path(mine))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("POST_NOT_FOUND"))
        assertThat(ids(similar(other, theirs))).doesNotContain(mine)
    }

    @Test
    fun `없는 글은 404이고 글 ID가 숫자가 아니면 400이다`() {
        val member = members.onboarded()

        http.get(member, path(Long.MAX_VALUE)).andExpect(status().isNotFound)
        http.get(member, "/api/v1/posts/abc/similar").andExpect(status().isBadRequest)
    }

    private fun similar(
        viewer: TestMember,
        postId: Long,
    ): JsonNode = http.data(viewer, path(postId))

    private fun ids(body: JsonNode): List<Long> = body.get("items").values().map { it.get("postId").asLong() }

    private fun emotionsOf(body: JsonNode): List<String> =
        body
            .get("items")
            .values()
            .mapNotNull {
                it
                    .get("monster")
                    .takeUnless { monster -> monster.isNull }
                    ?.get("emotion")
                    ?.asString()
            }

    private fun path(postId: Long) = "/api/v1/posts/$postId/similar"
}
