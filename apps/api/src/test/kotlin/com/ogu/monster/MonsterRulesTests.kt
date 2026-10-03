package com.ogu.monster

import com.ogu.TestcontainersConfiguration
import com.ogu.support.CoreLoopFixture
import com.ogu.support.HpLog
import com.ogu.support.MemberFixture
import com.ogu.support.MonsterDefeatedRecorder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T037: HP 반영 규칙(data-model.md 반영 규칙 1~4, US3-AC5, FR-006, FR-009, research R5).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MonsterRulesTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var defeated: MonsterDefeatedRecorder

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
    }

    @Test
    fun `US3-AC5 HP는 0에서 멈추고 DEFEATED, 이후 공격은 hp_before=hp_after=0으로 기록`() {
        val author = members.onboarded()
        val attackers = (1..4).map { members.onboarded() }
        val latecomer = members.onboarded()
        val postId = loop.postWithMonster(author)

        // 10 → 7 → 4 → 1 → 0(3을 줄여야 하지만 0에서 멈춘다)
        attackers.forEach { loop.comment(it, postId) }

        val monster = loop.monster(postId)!!
        assertThat(monster.hp).isZero()
        assertThat(monster.status).isEqualTo(MonsterStatus.DEFEATED)
        assertThat(defeatedAt(postId)).isNotNull()

        loop.likePost(latecomer, postId).andExpect(status().isOk)
        loop.comment(latecomer, postId)

        assertThat(loop.monster(postId)!!).isEqualTo(monster)
        assertThat(loop.hpLogs(postId))
            .containsExactly(
                HpLog(attackers[0].id, "COMMENT", postId, 3, 10, 7, false),
                HpLog(attackers[1].id, "COMMENT", postId, 3, 7, 4, false),
                HpLog(attackers[2].id, "COMMENT", postId, 3, 4, 1, false),
                HpLog(attackers[3].id, "COMMENT", postId, 3, 1, 0, false),
                HpLog(latecomer.id, "POST_LIKE", postId, 1, 0, 0, false),
                HpLog(latecomer.id, "COMMENT", postId, 3, 0, 0, false),
            )
    }

    @Test
    fun `MonsterDefeated는 처치될 때 한 번만 발행된다`() {
        val author = members.onboarded()
        val attackers = (1..5).map { members.onboarded() }
        val postId = loop.postWithMonster(author)

        attackers.forEach { loop.comment(it, postId) }
        attackers.forEach { loop.likePost(it, postId).andExpect(status().isOk) }

        assertThat(defeated.forPost(postId)).hasSize(1)
        val monsterId =
            jdbcTemplate.queryForObject("select id from monsters where post_id = ?", Long::class.java, postId)
        assertThat(defeated.forPost(postId).single().monsterId).isEqualTo(monsterId)
    }

    @Test
    fun `몬스터가 없으면 공격은 HP 기록 없이 통과`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        loop.likePost(fan, postId).andExpect(status().isOk)
        val commentId = loop.comment(fan, postId)
        loop.likeComment(fan, commentId).andExpect(status().isOk)

        assertThat(loop.monster(postId)).isNull()
        assertThat(
            jdbcTemplate.queryForObject(
                "select count(*) from monster_hp_log where member_id = ?",
                Int::class.java,
                fan.id,
            ),
        ).isZero()
        assertThat(jdbcTemplate.queryForObject("select like_count from posts where id = ?", Int::class.java, postId))
            .isEqualTo(1)
    }

    private fun defeatedAt(postId: Long): Any? =
        jdbcTemplate.queryForObject("select defeated_at from monsters where post_id = ?", Any::class.java, postId)
}
