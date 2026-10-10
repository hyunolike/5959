package com.ogu.raid

import com.ogu.TestcontainersConfiguration
import com.ogu.support.MemberFixture
import com.ogu.support.RaidFixture
import com.ogu.support.SafetyFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpMethod
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** T008: 레이드 조회와 공격(006 US1). */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RaidApiTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var redisTemplate: StringRedisTemplate

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var http: SafetyFixture
    private lateinit var raid: RaidFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        http = SafetyFixture(mockMvc, jdbcTemplate)
        raid = RaidFixture(jdbcTemplate, redisTemplate)
    }

    @Test
    fun `US1-AC1 조회에 감정, HP, 참여자 수, 내 기여가 있다`() {
        val bossId = raid.freshBoss(maxHp = 30, emotion = "ANXIETY")
        val member = members.onboarded()

        http
            .get(member, PATH)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.boss.bossId").value(bossId))
            .andExpect(jsonPath("$.data.boss.emotion").value("ANXIETY"))
            .andExpect(jsonPath("$.data.boss.maxHp").value(30))
            .andExpect(jsonPath("$.data.boss.hp").value(30))
            .andExpect(jsonPath("$.data.boss.status").value("ALIVE"))
            .andExpect(jsonPath("$.data.boss.participantCount").value(0))
            .andExpect(jsonPath("$.data.boss.spawnedAt").isNotEmpty)
            .andExpect(jsonPath("$.data.boss.endedAt").isEmpty)
            .andExpect(jsonPath("$.data.myDamage").value(0))
            .andExpect(jsonPath("$.data.nextBossAt").isEmpty)
            .andExpect(jsonPath("$.data.available").value(true))
            .andExpect(jsonPath("$.data.epoch").isNumber)
    }

    @Test
    fun `US1-AC2 공격하면 HP가 1 줄고 내 기여가 1 오른다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val member = members.onboarded()
        val other = members.onboarded()

        attack(member, bossId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.bossId").value(bossId))
            .andExpect(jsonPath("$.data.hp").value(29))
            .andExpect(jsonPath("$.data.maxHp").value(30))
            .andExpect(jsonPath("$.data.status").value("ALIVE"))
            .andExpect(jsonPath("$.data.myDamage").value(1))
            .andExpect(jsonPath("$.data.participantCount").value(1))
            .andExpect(jsonPath("$.data.defeated").value(false))
            .andExpect(jsonPath("$.data.cooldownMs").value(1000))
        attack(other, bossId).andExpect(jsonPath("$.data.hp").value(28))

        // 조회에는 내 기여만 실린다
        http
            .get(member, PATH)
            .andExpect(jsonPath("$.data.boss.hp").value(28))
            .andExpect(jsonPath("$.data.boss.participantCount").value(2))
            .andExpect(jsonPath("$.data.myDamage").value(1))
    }

    @Test
    fun `US1-AC3 1초 안의 두 번째 공격은 429 RAID_COOLDOWN이고 HP와 기여는 그대로다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val member = members.onboarded()
        attack(member, bossId).andExpect(status().isOk)

        attack(member, bossId)
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error.code").value("RAID_COOLDOWN"))
            .andExpect(header().string("Retry-After", "1"))

        http
            .get(member, PATH)
            .andExpect(jsonPath("$.data.boss.hp").value(29))
            .andExpect(jsonPath("$.data.myDamage").value(1))
        // 쿨다운이 지나면 다시 받는다
        Thread.sleep(COOLDOWN_MILLIS)
        attack(member, bossId).andExpect(status().isOk).andExpect(jsonPath("$.data.hp").value(28))
    }

    @Test
    fun `US1-AC4 같은 회원의 동시 공격은 하나만 반영된다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val member = members.onboarded()
        // 첫 요청이 Redis를 채우는 동안 나머지가 기다리지 않도록 미리 채운다
        http.get(member, PATH).andExpect(status().isOk)
        val pool = Executors.newFixedThreadPool(TABS)
        val start = CountDownLatch(1)

        val statuses =
            try {
                (1..TABS)
                    .map {
                        pool.submit(
                            Callable {
                                start.await()
                                attack(member, bossId).andReturn().response.status
                            },
                        )
                    }.also { start.countDown() }
                    .map { it.get(30, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }

        assertThat(statuses.count { it == 200 }).isEqualTo(1)
        assertThat(statuses.count { it == 429 }).isEqualTo(TABS - 1)
        assertThat(raid.redisHp()).isEqualTo(29)
    }

    @Test
    fun `US1-AC6 끝난 보스와 지난 보스는 409 RAID_BOSS_ENDED이다`() {
        val old = raid.freshBoss(maxHp = 30)
        val bossId = raid.freshBoss(maxHp = 1)
        val member = members.onboarded()
        val late = members.onboarded()

        attack(member, old).andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("RAID_BOSS_ENDED"))
        attack(member, bossId)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.hp").value(0))
            .andExpect(jsonPath("$.data.defeated").value(true))
            .andExpect(jsonPath("$.data.status").value("DEFEATED"))
        attack(late, bossId).andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("RAID_BOSS_ENDED"))

        // 처치는 응답하기 전에 기록에 남는다
        assertThat(raid.boss(bossId)).containsEntry("status", "DEFEATED").containsEntry("hp", 0)
        assertThat(raid.recordedDamage(bossId, member.id)).isEqualTo(1)
        // 끝난 뒤의 조회는 결과와 다음 보스가 나오는 때를 준다
        http
            .get(late, PATH)
            .andExpect(jsonPath("$.data.boss.status").value("DEFEATED"))
            .andExpect(jsonPath("$.data.boss.endedAt").isNotEmpty)
            .andExpect(jsonPath("$.data.boss.participantCount").value(1))
            .andExpect(jsonPath("$.data.myDamage").value(0))
            .andExpect(jsonPath("$.data.nextBossAt").isNotEmpty)
    }

    @Test
    fun `US1-AC7 로그인하지 않으면 401이고 온보딩 전 회원은 403이다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val signedUp = members.signedUp()

        mockMvc.perform(get(PATH)).andExpect(status().isUnauthorized)
        http.get(signedUp, PATH).andExpect(status().isForbidden)
        attack(signedUp, bossId)
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("ONBOARDING_REQUIRED"))
        assertThat(raid.boss(bossId)).containsEntry("hp", 30)
    }

    @Test
    fun `보스 ID가 빠졌거나 숫자가 아니면 400이다`() {
        raid.freshBoss(maxHp = 30)
        val member = members.onboarded()

        listOf("{}", """{"bossId":null}""", """{"bossId":"첫째"}""").forEach { body ->
            http
                .send(member, HttpMethod.POST, ATTACKS, body)
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"))
        }
    }

    @Test
    fun `US4-AC4 응답에 다른 회원의 정보와 순위가 없다`() {
        val bossId = raid.freshBoss(maxHp = 30)
        val member = members.onboarded()
        val other = members.onboarded()
        attack(other, bossId).andExpect(status().isOk)

        val attackBody = attack(member, bossId).andReturn().response.contentAsString
        val stateBody =
            mockMvc
                .perform(get(PATH).bearer(member.accessToken))
                .andReturn()
                .response.contentAsString

        listOf(attackBody, stateBody).forEach { body ->
            assertThat(body).doesNotContain("nickname", "memberId", "rank", "\"members\"", "\"${other.id}\"")
        }
    }

    private fun attack(
        member: TestMember,
        bossId: Long,
    ): ResultActions = http.send(member, HttpMethod.POST, ATTACKS, mapOf("bossId" to bossId))

    private companion object {
        const val PATH = "/api/v1/raid"
        const val ATTACKS = "/api/v1/raid/attacks"
        const val TABS = 6
        const val COOLDOWN_MILLIS = 1100L
    }
}
