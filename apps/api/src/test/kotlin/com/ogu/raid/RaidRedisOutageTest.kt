package com.ogu.raid

import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.MonsterDefeatedRecorder
import com.ogu.support.QueryCountConfiguration
import com.ogu.support.SafetyFixture
import com.ogu.support.TestAiConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration

/**
 * T013: Redis가 내려가 있으면 공격만 거절하고 나머지는 그대로다(006 US3-AC6, AC7, research R4). 공용 테스트 Redis를 멈추면
 * 다른 테스트가 깨지므로, 아무것도 듣지 않는 포트를 Redis 주소로 준 컨텍스트를 따로 띄운다. DB도 이 테스트만의 것이다.
 */
@SpringBootTest(properties = ["spring.data.redis.url=redis://localhost:1"])
@Import(RaidRedisOutageTest.WithoutRedis::class)
class RaidRedisOutageTest {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var http: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        http = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `US3-AC6 공격은 503 RAID_UNAVAILABLE이고 조회는 마지막 기록과 available false다`() {
        val member = members.onboarded()
        val bossId = recordedBoss(maxHp = 30, hp = 21)
        jdbcTemplate.update(
            "insert into raid_contribution (boss_id, member_id, damage, first_attack_at, updated_at) " +
                "values (?, ?, 4, now(), now())",
            bossId,
            member.id,
        )

        val started = System.nanoTime()
        http
            .send(member, HttpMethod.POST, "/api/v1/raid/attacks", mapOf("bossId" to bossId))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("RAID_UNAVAILABLE"))
        // 거절은 기다리게 하지 않는다
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))

        http
            .get(member, "/api/v1/raid")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(false))
            .andExpect(jsonPath("$.data.boss.bossId").value(bossId))
            .andExpect(jsonPath("$.data.boss.hp").value(21))
            .andExpect(jsonPath("$.data.boss.participantCount").value(1))
            .andExpect(jsonPath("$.data.myDamage").value(4))
        // 반영되지 않았다
        assertThat(jdbcTemplate.queryForObject("select hp from raid_boss where id = ?", Int::class.java, bossId))
            .isEqualTo(21)
    }

    @Test
    fun `US3-AC7 글쓰기와 댓글, 공감, 알림 목록은 그대로 된다`() {
        val coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        val author = members.onboarded()
        val other = members.onboarded()

        val postId = coreLoop.createPost(author, "[실패] Redis가 없어도 쓰는 글")
        coreLoop.comment(other, postId, "댓글도 달려요")
        coreLoop.likePost(other, postId).andExpect(status().is2xxSuccessful)

        http.get(author, "/api/v1/notifications").andExpect(status().isOk)
        http.get(other, "/api/v1/feed").andExpect(status().isOk)
        http.awaitNotifications(author, "POST_COMMENT", 1)
    }

    private fun recordedBoss(
        maxHp: Int,
        hp: Int,
    ): Long {
        jdbcTemplate.update("update raid_boss set status = 'RETREATED', ended_at = now() where status = 'ALIVE'")
        return jdbcTemplate.queryForObject(
            """
            insert into raid_boss (emotion, max_hp, hp, participant_count, status, spawned_at)
            values ('LETHARGY', ?, ?, 1, 'ALIVE', now())
            returning id
            """.trimIndent(),
            Long::class.java,
            maxHp,
            hp,
        )!!
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestAiConfiguration::class, QueryCountConfiguration::class, MonsterDefeatedRecorder::class)
    class WithoutRedis {
        @Bean
        @ServiceConnection
        fun postgresContainer(): PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
    }
}
