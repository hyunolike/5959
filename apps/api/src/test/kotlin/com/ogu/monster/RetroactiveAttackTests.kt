package com.ogu.monster

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.EmotionAnalyzed
import com.ogu.emotion.EmotionType
import com.ogu.emotion.Intensity
import com.ogu.support.CoreLoopFixture
import com.ogu.support.HpLog
import com.ogu.support.MemberFixture
import com.ogu.support.MonsterDefeatedRecorder
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext

/**
 * T039: 분석 중에 쌓인 공격의 소급 반영(US3-AC9, FR-006a, research R4). 가짜 분석기가 계속 실패하는 글(`[실패]`)을
 * 쓰고, 분석 완료(`EmotionAnalyzed`)는 테스트가 원하는 때에 발행해 몬스터를 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RetroactiveAttackTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

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
    fun `US3-AC9 분석 중에 다른 회원 둘이 공감하고 하나가 댓글을 달면 몬스터 HP는 최대 HP − 5이고 기록 3개는 retroactive=true`() {
        val author = members.onboarded()
        val (fan1, fan2, commenter) = (1..3).map { members.onboarded() }
        val postId = loop.postWithoutMonster(author)
        loop.likePost(fan1, postId).andExpect(status().isOk)
        loop.likePost(fan2, postId).andExpect(status().isOk)
        loop.comment(commenter, postId)
        assertThat(loop.monster(postId)).isNull()

        analysisFinished(postId, Intensity.MEDIUM)

        val monster = loop.awaitMonster(postId)
        assertThat(monster).isEqualTo(MonsterView(EmotionType.ANXIETY, 15, 20, MonsterStatus.ALIVE))
        val logs = loop.hpLogs(postId)
        assertThat(logs.map { Triple(it.memberId, it.action, it.targetId) })
            .containsExactlyInAnyOrder(
                Triple(fan1.id, "POST_LIKE", postId),
                Triple(fan2.id, "POST_LIKE", postId),
                Triple(commenter.id, "COMMENT", postId),
            )
        assertThat(logs.map { it.retroactive }).containsOnly(true)
        assertHpChain(logs, from = 20, to = 15)
    }

    @Test
    fun `그 사이 취소된 공감과 지운 댓글은 반영하지 않음`() {
        val author = members.onboarded()
        val canceller = members.onboarded()
        val deleter = members.onboarded()
        val twice = members.onboarded()
        val replier = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        // 공감했다가 취소했다
        loop.likePost(canceller, postId).andExpect(status().isOk)
        loop.unlikePost(canceller, postId).andExpect(status().isOk)
        // 하나뿐인 댓글을 지웠다. 그 댓글에 받은 공감도 반영하지 않는다
        val deletedComment = loop.comment(deleter, postId)
        loop.likeComment(fan, deletedComment).andExpect(status().isOk)
        loop.deleteComment(deletedComment)
        // 첫 댓글은 지웠지만 둘째 댓글이 살아 있다: 댓글 하나로 센다
        val firstOfTwice = loop.comment(twice, postId)
        val secondOfTwice = loop.comment(twice, postId)
        loop.deleteComment(firstOfTwice)
        // 살아 있는 댓글이 답글뿐이어도 센다
        loop.comment(replier, postId, parentId = secondOfTwice)
        // 살아 있는 댓글에 받은 공감은 반영한다
        loop.likeComment(fan, secondOfTwice).andExpect(status().isOk)

        analysisFinished(postId, Intensity.HIGH)

        val monster = loop.awaitMonster(postId)
        assertThat(monster.hp).isEqualTo(30 - 3 - 3 - 1)
        assertThat(loop.hpLogs(postId).map { Triple(it.memberId, it.action, it.targetId) })
            .containsExactlyInAnyOrder(
                Triple(twice.id, "COMMENT", postId),
                Triple(replier.id, "COMMENT", postId),
                Triple(fan.id, "COMMENT_LIKE", secondOfTwice),
            )
    }

    @Test
    fun `작성자 행동 제외`() {
        val author = members.onboarded()
        val commenter = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        val commentId = loop.comment(commenter, postId)
        loop.comment(author, postId, "작성자 댓글")
        loop.comment(author, postId, "작성자 답글", parentId = commentId)
        loop.likeComment(author, commentId).andExpect(status().isOk)

        analysisFinished(postId, Intensity.LOW)

        assertThat(loop.awaitMonster(postId).hp).isEqualTo(7)
        assertThat(loop.hpLogs(postId).map { it.memberId }).containsExactly(commenter.id)
    }

    @Test
    fun `소급 반영으로 0이 되면 DEFEATED로 생성`() {
        val author = members.onboarded()
        val attackers = (1..4).map { members.onboarded() }
        val postId = loop.postWithoutMonster(author)
        attackers.forEach { loop.comment(it, postId) }

        analysisFinished(postId, Intensity.LOW)

        val monster = loop.awaitMonster(postId)
        assertThat(monster).isEqualTo(MonsterView(EmotionType.ANXIETY, 0, 10, MonsterStatus.DEFEATED))
        val logs = loop.hpLogs(postId)
        assertThat(logs).hasSize(4)
        assertHpChain(logs, from = 10, to = 0)
        assertThat(
            jdbcTemplate.queryForObject("select defeated_at from monsters where post_id = ?", Any::class.java, postId),
        ).isNotNull()
        // 기록은 커밋 뒤에 담기므로 몬스터가 보인 직후에는 아직 없을 수 있다
        await().atMost(CoreLoopFixture.AWAIT_LIMIT).until { defeated.forPost(postId).isNotEmpty() }
        assertThat(defeated.forPost(postId)).hasSize(1)
        // 같은 트랜잭션에서 생성 이벤트가 처치 이벤트보다 먼저 나간다(004 research R8)
        val monsterId =
            jdbcTemplate.queryForObject("select id from monsters where post_id = ?", Long::class.java, postId)!!
        assertThat(defeated.monsterEventsFor(postId))
            .containsExactly(
                MonsterSpawned(postId, monsterId, defaulted = false),
                MonsterDefeated(postId, monsterId, retroactive = true),
            )
    }

    @Test
    fun `몬스터가 생긴 뒤의 공격은 소급 기록과 겹치지 않고 retroactive=false다`() {
        val author = members.onboarded()
        val fan = members.onboarded()
        val postId = loop.postWithoutMonster(author)
        loop.likePost(fan, postId).andExpect(status().isOk)
        analysisFinished(postId, Intensity.LOW)
        loop.awaitMonster(postId)

        loop.unlikePost(fan, postId).andExpect(status().isOk)
        loop.likePost(fan, postId).andExpect(status().isOk)
        loop.comment(fan, postId)

        assertThat(loop.monster(postId)!!.hp).isEqualTo(6)
        assertThat(loop.hpLogs(postId))
            .containsExactly(
                HpLog(fan.id, "POST_LIKE", postId, 1, 10, 9, true),
                HpLog(fan.id, "COMMENT", postId, 3, 9, 6, false),
            )
    }

    /** 분석이 끝났다고 알린다. 커밋 뒤 MonsterFactory가 비동기로 몬스터를 만든다. */
    private fun analysisFinished(
        postId: Long,
        intensity: Intensity,
    ) {
        transactionTemplate.executeWithoutResult {
            events.publishEvent(EmotionAnalyzed(postId, EmotionType.ANXIETY, intensity, defaulted = false))
        }
    }

    /** 기록을 순서대로 이으면 [from]에서 [to]까지 빈틈없이 이어진다. */
    private fun assertHpChain(
        logs: List<HpLog>,
        from: Int,
        to: Int,
    ) {
        assertThat(logs.first().hpBefore).isEqualTo(from)
        logs.zipWithNext().forEach { (prev, next) -> assertThat(next.hpBefore).isEqualTo(prev.hpAfter) }
        assertThat(logs.last().hpAfter).isEqualTo(to)
        logs.forEach { assertThat(it.hpAfter).isEqualTo(maxOf(it.hpBefore - it.hpDelta, 0)) }
    }
}
