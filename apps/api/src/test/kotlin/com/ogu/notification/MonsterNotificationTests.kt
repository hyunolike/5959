package com.ogu.notification

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.EmotionAnalyzed
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterDefeated
import com.ogu.monster.MonsterSpawned
import com.ogu.monster.MonsterStatus
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.test.EnableScenarios
import org.springframework.modulith.test.Scenario
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

/**
 * T019: 몬스터 생성과 처치 알림(US1-AC3, US1-AC4, 명확화 1, research R8, R9). 최대 HP 10인 몬스터(`[불안:낮음]`)에
 * 공감(1)과 회원별 첫 댓글(3)로 HP를 줄인다.
 */
@SpringBootTest
@EnableScenarios
@Import(TestcontainersConfiguration::class)
class MonsterNotificationTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var redis: StringRedisTemplate

    @Autowired
    lateinit var container: RedisMessageListenerContainer

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var support: NotificationTestSupport

    @BeforeEach
    fun setUp() {
        val mockMvc =
            MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        support = NotificationTestSupport(jdbcTemplate, redis, container)
    }

    @Test
    fun `US1-AC3 몬스터가 생기면 글쓴이에게 MONSTER_SPAWNED`() {
        val author = members.onboarded()

        val postId = loop.postWithMonster(author)

        val row = awaitTypes(author.id, "MONSTER_SPAWNED").single()
        support.awaitListenersIdle()
        assertThat(support.notificationsOf(author.id)).containsExactly(row)
        assertThat(row.postId).isEqualTo(postId)
        assertThat(row.monsterId).isEqualTo(monsterId(postId))
        assertThat(row.latestActorId).isNull()
        assertThat(row.dedupKey).isEqualTo("SPAWNED:${row.monsterId}")
    }

    @Test
    fun `US1-AC3 분석에 실패해 생긴 기본 몬스터(defaulted=true)도 MONSTER_SPAWNED`(scenario: Scenario) {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)

        scenario
            .publish(EmotionAnalyzed(postId, EmotionType.LETHARGY, Intensity.LOW, defaulted = true))
            .andWaitForStateChange { support.notificationsOf(author.id, "MONSTER_SPAWNED") }
            .andVerify { rows -> assertThat(rows.single().monsterId).isEqualTo(monsterId(postId)) }
    }

    @Test
    fun `US1-AC4 처치되면 글쓴이는 MONSTER_DEFEATED, HP를 줄인 회원은 MONSTER_DEFEATED_TOGETHER를 한 번씩 받는다`(scenario: Scenario) {
        val author = members.onboarded()
        val twice = members.onboarded()
        val once = members.onboarded()
        val killer = members.onboarded()
        val cheerer = members.onboarded()
        val postId = loop.postWithMonster(author)
        val monsterId = monsterId(postId)
        // 한 회원이 공감과 댓글로 기록 행이 둘이다
        likeAndComment(twice, postId)
        likeAndComment(once, postId)
        loop.comment(killer, postId)
        assertThat(loop.monster(postId)!!.status).isEqualTo(MonsterStatus.DEFEATED)
        // 처치 뒤 남긴 응원은 hp_before = hp_after = 0으로 남는다
        loop.likePost(cheerer, postId).andExpect(status().isOk)
        assertThat(loop.hpLogs(postId).filter { it.memberId == cheerer.id }.map { it.hpBefore to it.hpAfter })
            .containsExactly(0 to 0)

        awaitTypes(author.id, "MONSTER_DEFEATED")
        support.awaitListenersIdle()
        // 응원한 회원까지 기록에 들어간 뒤 처치 이벤트가 다시 와도 받는 사람은 그대로다
        scenario
            .publish(MonsterDefeated(postId, monsterId))
            .andWaitForStateChange { support.incompletePublications() == 0 }
        support.awaitListenersIdle()

        val toAuthor = support.notificationsOf(author.id, *MONSTER_TYPES)
        assertThat(toAuthor.map { it.type }).containsExactly("MONSTER_SPAWNED", "MONSTER_DEFEATED")
        assertThat(toAuthor.last().dedupKey).isEqualTo("DEFEATED:$monsterId")
        listOf(twice, once, killer).forEach { member ->
            val rows = support.notificationsOf(member.id)
            assertThat(rows.map { it.type }).containsExactly("MONSTER_DEFEATED_TOGETHER")
            assertThat(rows.single().monsterId).isEqualTo(monsterId)
            assertThat(rows.single().postId).isEqualTo(postId)
            assertThat(rows.single().latestActorId).isNull()
            assertThat(rows.single().dedupKey).isEqualTo("DEFEATED:$monsterId")
        }
        assertThat(support.notificationsOf(cheerer.id)).isEmpty()
        assertThat(monsterApi.damagerIds(monsterId)).containsExactlyInAnyOrder(twice.id, once.id, killer.id)
    }

    @Test
    fun `US1-AC4 마지막 공격으로 HP를 0으로 만든 회원도 MONSTER_DEFEATED_TOGETHER를 받는다`() {
        val author = members.onboarded()
        val (first, second, third) = (1..3).map { members.onboarded() }
        val finisher = members.onboarded()
        val postId = loop.postWithMonster(author)
        likeAndComment(first, postId)
        likeAndComment(second, postId)
        loop.likePost(third, postId).andExpect(status().isOk)

        // HP 1에서 공감 하나로 0이 된다
        loop.likePost(finisher, postId).andExpect(status().isOk)

        assertThat(loop.hpLogs(postId).last().let { Triple(it.memberId, it.hpBefore, it.hpAfter) })
            .isEqualTo(Triple(finisher.id, 1, 0))
        val row = awaitTypes(finisher.id, "MONSTER_DEFEATED_TOGETHER").single()
        support.awaitListenersIdle()
        assertThat(support.notificationsOf(finisher.id)).containsExactly(row)
        assertThat(row.monsterId).isEqualTo(monsterId(postId))
    }

    @Test
    fun `소급 반영으로 처치된 채 생기면 글쓴이의 seq는 나타났어요 다음 처치됐어요 순서`(scenario: Scenario) {
        val author = members.onboarded()
        val (first, second, third) = (1..3).map { members.onboarded() }
        val postId = loop.postWithoutMonster(author)
        likeAndComment(first, postId)
        likeAndComment(second, postId)
        loop.comment(third, postId)

        scenario
            .publish(EmotionAnalyzed(postId, EmotionType.ANXIETY, Intensity.LOW, defaulted = false))
            .andWaitForStateChange { support.notificationsOf(author.id, "MONSTER_DEFEATED") }

        support.awaitListenersIdle()
        assertThat(loop.monster(postId)!!.status).isEqualTo(MonsterStatus.DEFEATED)
        val toAuthor = support.notificationsOf(author.id, *MONSTER_TYPES)
        assertThat(toAuthor.map { it.type }).containsExactly("MONSTER_SPAWNED", "MONSTER_DEFEATED")
        assertThat(toAuthor[0].seq).isLessThan(toAuthor[1].seq)
        listOf(first, second, third).forEach { member ->
            assertThat(support.notificationsOf(member.id, *MONSTER_TYPES).map { it.type })
                .containsExactly("MONSTER_DEFEATED_TOGETHER")
        }
    }

    @Test
    fun `MonsterDefeated가 먼저 처리돼도 나타났어요를 먼저 만들고, 뒤늦게 온 MonsterSpawned는 아무것도 바꾸지 않음`(scenario: Scenario) {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        // 생성 알림을 처리하기 전의 몬스터를 흉내 내려고 아직 알림이 없는 몬스터 ID로 이벤트를 차례로 보낸다
        val monsterId = UNSEEN_MONSTER_BASE + postId

        scenario
            .publish(MonsterDefeated(postId, monsterId))
            .andWaitForStateChange { support.notificationsOf(author.id, "MONSTER_DEFEATED") }
        support.awaitListenersIdle()
        val before = support.notificationsOf(author.id)
        assertThat(before.map { it.type to it.dedupKey })
            .containsExactly("MONSTER_SPAWNED" to "SPAWNED:$monsterId", "MONSTER_DEFEATED" to "DEFEATED:$monsterId")
        assertThat(before[0].seq).isLessThan(before[1].seq)
        val lastSeq = support.lastSeq(author.id)

        scenario
            .publish(MonsterSpawned(postId, monsterId, defaulted = false))
            .andWaitForStateChange { support.incompletePublications() == 0 }

        support.awaitListenersIdle()
        assertThat(support.notificationsOf(author.id)).isEqualTo(before)
        assertThat(support.lastSeq(author.id)).isEqualTo(lastSeq)
    }

    @Test
    fun `지운 글의 몬스터 이벤트는 알림을 만들지 않음`(scenario: Scenario) {
        val author = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.deletePost(postId)
        val monsterId = UNSEEN_MONSTER_BASE + postId

        scenario
            .publish(MonsterSpawned(postId, monsterId, defaulted = false))
            .andWaitForStateChange { support.incompletePublications() == 0 }
        scenario
            .publish(MonsterDefeated(postId, monsterId))
            .andWaitForStateChange { support.incompletePublications() == 0 }

        support.awaitListenersIdle()
        assertThat(support.notificationsOf(author.id)).isEmpty()
    }

    private fun likeAndComment(
        member: TestMember,
        postId: Long,
    ) {
        loop.likePost(member, postId).andExpect(status().isOk)
        loop.comment(member, postId)
    }

    private fun monsterId(postId: Long): Long =
        jdbcTemplate.queryForObject("select id from monsters where post_id = ?", Long::class.java, postId)!!

    private fun awaitTypes(
        receiverId: Long,
        vararg types: String,
    ): List<NotificationRow> {
        await()
            .atMost(NotificationTestSupport.AWAIT_LIMIT)
            .pollInterval(NotificationTestSupport.POLL)
            .until { support.notificationsOf(receiverId, *types).isNotEmpty() }
        return support.notificationsOf(receiverId, *types)
    }

    private companion object {
        val MONSTER_TYPES = arrayOf("MONSTER_SPAWNED", "MONSTER_DEFEATED", "MONSTER_DEFEATED_TOGETHER")
        const val UNSEEN_MONSTER_BASE = 9_000_000_000L
    }
}
