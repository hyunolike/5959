package com.ogu.shared.config

import com.ogu.TestcontainersConfiguration
import com.ogu.emotion.application.AnalysisStore
import com.ogu.monster.MonsterApi
import com.ogu.post.PostCreated
import com.ogu.shared.lock.PostLock
import com.ogu.support.MemberFixture
import com.ogu.support.TestMember
import com.ogu.support.bearer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.doCallRealMethod
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * 리스너가 한 번 실패해도 재기동 없이 끝나지 않은 이벤트 발행을 다시 보내 결국 처리된다(constitution V).
 * 정기 재전송은 끄고 [EventPublicationResubmitter.resubmit]을 직접 부른다. 나이 기준은 0초로 둔다.
 */
@SpringBootTest(
    properties = [
        "ogu.emotion.retry.scheduler-enabled=false",
        "ogu.events.resubmit.enabled=false",
        "ogu.events.resubmit.older-than=0s",
    ],
)
@Import(TestcontainersConfiguration::class)
class EventResubmissionTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var resubmitter: EventPublicationResubmitter

    @Autowired
    lateinit var monsterApi: MonsterApi

    @MockitoSpyBean
    lateinit var analysisStore: AnalysisStore

    @MockitoSpyBean
    lateinit var postLock: PostLock

    private val jsonMapper = JsonMapper.builder().build()
    private lateinit var mockMvc: MockMvc
    private lateinit var member: TestMember

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        member = MemberFixture(mockMvc).onboarded()
    }

    @Test
    fun `분석 행을 만드는 리스너가 한 번 실패해도 재전송으로 행이 생기고 몬스터까지 만들어진다`() {
        doThrow(IllegalStateException("일시 장애"))
            .doCallRealMethod()
            .`when`(analysisStore)
            .createPending(any(PostCreated::class.java) ?: PLACEHOLDER)

        val postId = createPost("[불안:낮음] 리스너 실패")

        verify(analysisStore, timeout(AWAIT_MILLIS)).createPending(any(PostCreated::class.java) ?: PLACEHOLDER)
        assertThat(analysisRows(postId)).isZero()

        await().atMost(AWAIT_LIMIT).pollInterval(POLL).untilAsserted {
            resubmitter.resubmit()
            assertThat(analysisRows(postId)).isEqualTo(1)
        }
        await().atMost(AWAIT_LIMIT).pollInterval(POLL).until { monsterApi.findByPostIds(listOf(postId)).isNotEmpty() }
        assertThat(analysisRows(postId)).isEqualTo(1)
    }

    @Test
    fun `몬스터를 만드는 리스너가 한 번 실패해도 재전송으로 몬스터가 하나 생긴다`() {
        doThrow(IllegalStateException("일시 장애")).doCallRealMethod().`when`(postLock).lock(anyLong())

        val postId = createPost("[짜증:보통] 몬스터 리스너 실패")

        verify(postLock, timeout(AWAIT_MILLIS)).lock(postId)
        assertThat(monsterApi.findByPostIds(listOf(postId))).isEmpty()

        await().atMost(AWAIT_LIMIT).pollInterval(POLL).untilAsserted {
            resubmitter.resubmit()
            assertThat(monsterApi.findByPostIds(listOf(postId))).isNotEmpty()
        }
        val monsters = jdbcTemplate.queryForObject(MONSTER_COUNT, Int::class.java, postId)
        assertThat(monsters).isEqualTo(1)
        assertThat(monsterApi.findByPostIds(listOf(postId)).getValue(postId).maxHp).isEqualTo(20)
    }

    private fun analysisRows(postId: Long): Int = jdbcTemplate.queryForObject(ANALYSIS_COUNT, Int::class.java, postId)!!

    private fun createPost(content: String): Long {
        val body = mapOf("content" to content, "commentTone" to "COMFORT_ME")
        val response =
            mockMvc
                .perform(
                    post("/api/v1/posts")
                        .bearer(member.accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(body)),
                ).andExpect(status().isCreated)
                .andReturn()
                .response.contentAsString
        return jsonMapper
            .readTree(response)
            .get("data")
            .get("postId")
            .asLong()
    }

    companion object {
        private const val AWAIT_MILLIS = 10_000L
        private const val MONSTER_COUNT = "select count(*) from monsters where post_id = ?"
        private const val ANALYSIS_COUNT = "select count(*) from emotion_analysis where post_id = ?"
        private val AWAIT_LIMIT: Duration = Duration.ofSeconds(15)
        private val POLL: Duration = Duration.ofMillis(200)

        /** Mockito 매처는 null을 돌려주므로 Kotlin의 non-null 인자 자리에 넣을 자리표시 값. */
        private val PLACEHOLDER = PostCreated(0, 0, "", Instant.EPOCH)
    }
}
