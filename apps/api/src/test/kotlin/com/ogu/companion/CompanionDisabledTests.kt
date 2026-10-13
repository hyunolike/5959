package com.ogu.companion

import com.ogu.TestcontainersConfiguration
import com.ogu.companion.application.CompanionProperties
import com.ogu.companion.application.ReactionRunner
import com.ogu.monster.MonsterApi
import com.ogu.post.CompanionReactionCheck
import com.ogu.post.PostCompanionApi
import com.ogu.support.CompanionFixture
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.ScriptedCompanionCommentWriter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration

/**
 * 기능이 꺼져 있을 때(009 US5-AC1, research R12). 아무 설정 없이 띄운 컨텍스트가 곧 꺼진 상태다. 운영에도 이대로 나간다.
 * 시계를 움직이지 않으므로 반응 기록의 때를 앞으로 옮겨 "기다리는 시간이 지난 글"을 만든다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CompanionDisabledTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    @Autowired
    lateinit var properties: CompanionProperties

    @Autowired
    lateinit var runner: ReactionRunner

    @Autowired
    lateinit var writer: ScriptedCompanionCommentWriter

    @Autowired
    lateinit var companionApi: PostCompanionApi

    private lateinit var members: MemberFixture
    private lateinit var loop: CoreLoopFixture
    private lateinit var companions: CompanionFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        loop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        companions = CompanionFixture(jdbcTemplate)
    }

    @Test
    fun `US5-AC1 따로 켜지 않으면 꺼져 있고 설정의 기본값은 스펙과 같다`() {
        assertThat(properties.enabled).isFalse()
        assertThat(properties.wait).isEqualTo(Duration.ofMinutes(10))
        assertThat(properties.maxAge).isEqualTo(Duration.ofHours(24))
        assertThat(properties.dailyLimit).isEqualTo(45)
        assertThat(properties.maxAttempts).isEqualTo(3)
    }

    @Test
    fun `US5-AC1 꺼져 있으면 반응하지 않고 이미 남긴 댓글과 공감은 그대로 남는다`() {
        val author = members.onboarded()
        val before = loop.postWithoutMonster(author)
        companionApi.react(before, companions.memberIds[0], "켜져 있을 때 남긴 댓글이에요.", companions.memberIds)
        val postId = loop.postWithoutMonster(author)
        companions.awaitReady(postId)
        companions.backdate(postId, Duration.ofMinutes(20))

        assertThat(runner.attempt(postId)).isFalse()
        assertThat(runner.runDue()).isZero()

        // 기록은 꺼져 있어도 생긴다. 다시 켜면 24시간 안의 글은 반응을 받는다
        assertThat(companions.reaction(postId)!!["status"]).isEqualTo("WAITING")
        assertThat(companions.comments(postId)).isEmpty()
        assertThat(companions.likers(postId)).isEmpty()
        assertThat(writer.callsOf(postId)).isZero()
        assertThat(companions.comments(before)).hasSize(1)
        assertThat(companions.likers(before)).hasSize(1)
    }

    @Test
    fun `US2-AC10 꺼져 있으면 뒤따르는 친구의 차례가 되어도 맡지 않는다`() {
        val postId = loop.postWithoutMonster(members.onboarded())
        companions.awaitReady(postId)
        // 켜져 있을 때 세 명 가운데 한 명이 반응하고 다음 차례를 기다리던 기록
        val ids = companions.memberIds
        companionApi.react(postId, ids[0], "켜져 있을 때 남긴 댓글", ids, CompanionReactionCheck(limit = 3))
        jdbcTemplate.update("update companion_reaction set target_count = 3, done_count = 1 where post_id = ?", postId)
        companions.backdate(postId, Duration.ofMinutes(30))

        assertThat(runner.attempt(postId)).isFalse()
        assertThat(runner.runDue()).isZero()

        assertThat(companions.reaction(postId)!!["status"]).isEqualTo("WAITING")
        assertThat(companions.comments(postId)).hasSize(1)
        assertThat(writer.callsOf(postId)).isZero()
    }

    @Test
    fun `US7-AC2 따로 정하지 않으면 수의 무게는 60과 30과 10이고 뒤따르는 간격은 3분에서 10분이다`() {
        assertThat(properties.countWeights).containsExactly(60, 30, 10)
        assertThat(properties.followUp.minGap).isEqualTo(Duration.ofMinutes(3))
        assertThat(properties.followUp.maxGap).isEqualTo(Duration.ofMinutes(10))
    }
}
