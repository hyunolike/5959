package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.safety.application.SafetyBackfill
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
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
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T050: 안전 기능 전에 쓰인 글과 댓글을 한 번 훑는다(005 research R14). 묶음을 둘씩으로 줄여 여러 묶음에 걸친 진행을 본다.
 * 기동할 때 저절로 도는 것은 끄고 테스트가 직접 부른다.
 */
@SpringBootTest(properties = ["ogu.safety.backfill.enabled=false", "ogu.safety.backfill.batch-size=2"])
@Import(TestcontainersConfiguration::class)
class SafetyBackfillTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var backfill: SafetyBackfill

    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        val mockMvc: MockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        // 다른 테스트가 남긴 글은 이미 훑은 것으로 두고, 여기서 만드는 것만 훑게 한다
        startFrom("POST", maxId("posts"))
        startFrom("COMMENT", maxId("comments"))
    }

    @Test
    fun `위기 글은 숨기고 알리며 우려는 기록만 하고, 끝나면 다시 돌지 않는다`() {
        val author = members.onboarded()
        val viewer = members.onboarded()
        val crisis = safety.insertPost(author, SafetyFixture.CRISIS_TEXT)
        val concern = safety.insertPost(author, SafetyFixture.CONCERN_TEXT)
        val safe = safety.insertPost(author, SafetyFixture.SAFE_TEXT)
        val later = safety.insertPost(author, "죽 고 싶 다는 생각이 들어요")
        val crisisComment = insertComment(viewer, safe, "저도 죽고싶어요")

        val first = backfill.run()

        // 글 넷과 댓글 하나를 둘씩 세 묶음과 한 묶음으로 읽었다
        assertThat(first.scanned).isEqualTo(5)
        assertThat(safety.postState(crisis)).containsEntry("hidden", true).containsEntry("hidden_reason", "RISK")
        assertThat(safety.postState(later)).containsEntry("hidden", true).containsEntry("risk_level", "CRISIS")
        assertThat(safety.postState(concern)).containsEntry("hidden", false).containsEntry("risk_level", "CONCERN")
        assertThat(safety.postState(safe)).containsEntry("hidden", false).containsEntry("risk_level", "NONE")
        assertThat(safety.commentState(crisisComment)).containsEntry("hidden", true)
        assertThat(safety.feedPostIds(viewer)).contains(concern, safe).doesNotContain(crisis, later)
        // 키워드 판정만으로 닫힌 기록이고 AI 분류를 기다리지 않는다. 위험이 없으면 기록도 없다
        assertThat(assessments("POST", crisis)).containsExactly("CRISIS:FALLBACK")
        assertThat(assessments("POST", concern)).containsExactly("CONCERN:FALLBACK")
        assertThat(assessments("POST", safe)).isEmpty()
        assertThat(assessments("COMMENT", crisisComment)).containsExactly("CRISIS:FALLBACK")
        // 위기만 알린다
        safety.awaitNotifications(author, "SUPPORT_NOTICE", 2)
        safety.awaitNotifications(viewer, "SUPPORT_NOTICE", 1)
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE"))
            .containsExactlyInAnyOrder("RISK:POST:$crisis:CRISIS", "RISK:POST:$later:CRISIS")
        assertThat(finished("POST")).isTrue()
        assertThat(finished("COMMENT")).isTrue()

        // 끝난 뒤에는 새 글이 있어도 돌지 않는다(새 글은 저장할 때 판정된다)
        val afterwards = safety.insertPost(author, SafetyFixture.CRISIS_TEXT)
        assertThat(backfill.run().scanned).isZero()
        assertThat(safety.postState(afterwards)).containsEntry("hidden", false)
        safety.awaitListenersIdle()
        assertThat(safety.notificationKeys(author, "SUPPORT_NOTICE")).hasSize(2)
    }

    @Test
    fun `중간에 멈췄으면 표지 다음부터 이어서 하고, 이미 판정된 글은 다시 보지 않는다`() {
        val author = members.onboarded()
        val done = safety.insertPost(author, SafetyFixture.CRISIS_TEXT)
        val screened = coreLoop.createPost(author, SafetyFixture.CONCERN_TEXT)
        val pending = safety.insertPost(author, SafetyFixture.CRISIS_TEXT)
        safety.awaitListenersIdle()
        val before = assessments("POST", screened)
        // 첫 글까지 훑고 멈춘 상태
        startFrom("POST", done)

        val result = backfill.run()

        assertThat(result.scanned).isEqualTo(2)
        assertThat(safety.postState(done)).containsEntry("hidden", false)
        assertThat(assessments("POST", done)).isEmpty()
        assertThat(safety.postState(pending)).containsEntry("hidden", true)
        // 저장할 때 판정된 글은 기록이 늘지 않는다
        assertThat(assessments("POST", screened)).isEqualTo(before).hasSize(1)
        assertThat(lastId("POST")).isEqualTo(pending)
        assertThat(finished("POST")).isTrue()
    }

    private fun insertComment(
        author: TestMember,
        postId: Long,
        content: String,
    ): Long =
        jdbcTemplate.queryForObject(
            """
            insert into comments (post_id, author_id, content, created_at, updated_at)
            values (?, ?, ?, now(), now())
            returning id
            """.trimIndent(),
            Long::class.java,
            postId,
            author.id,
            content,
        )!!

    private fun assessments(
        type: String,
        targetId: Long,
    ): List<String> =
        jdbcTemplate.queryForList(
            """
            select level || ':' || status from risk_assessment
            where target_type = ? and target_id = ? order by id
            """.trimIndent(),
            String::class.java,
            type,
            targetId,
        )

    private fun startFrom(
        type: String,
        lastId: Long,
    ) {
        jdbcTemplate.update(
            "update safety_backfill set last_id = ?, finished_at = null where target_type = ?",
            lastId,
            type,
        )
    }

    private fun maxId(table: String): Long {
        val sql = "select coalesce(max(id), 0) from $table"
        return jdbcTemplate.queryForObject(sql, Long::class.java)!!
    }

    private fun lastId(type: String): Long =
        jdbcTemplate.queryForObject(
            "select last_id from safety_backfill where target_type = ?",
            Long::class.java,
            type,
        )!!

    private fun finished(type: String): Boolean =
        jdbcTemplate.queryForObject(
            "select finished_at is not null from safety_backfill where target_type = ?",
            Boolean::class.java,
            type,
        )!!
}
