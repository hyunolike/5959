package com.ogu.safety

import com.ogu.TestcontainersConfiguration
import com.ogu.monster.MonsterApi
import com.ogu.support.CoreLoopFixture
import com.ogu.support.MemberFixture
import com.ogu.support.SafetyFixture
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
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T015: 목록에 있는 위기 표현이 든 글은 한 번도 공개되지 않는다(005 SC-001, research R2). 여러 회원이 위기 글을 동시에 쓰는
 * 동안 다른 회원이 피드를 계속 불러도 그 글이 나오지 않아야 한다. 저장과 숨김이 따로 커밋되면 이 테스트가 잡는다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ScreeningVisibilityTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var monsterApi: MonsterApi

    private lateinit var mockMvc: MockMvc
    private lateinit var members: MemberFixture
    private lateinit var coreLoop: CoreLoopFixture
    private lateinit var safety: SafetyFixture

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        coreLoop = CoreLoopFixture(mockMvc, jdbcTemplate, monsterApi)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
    }

    @Test
    fun `위기 표현이 든 글 100개를 동시에 쓰는 동안 다른 회원의 피드에 한 번도 나오지 않는다`() {
        // 작성 제한이 회원마다 1시간 10개라 작성자 10명이 10개씩 쓴다
        val authors = (1..AUTHORS).map { members.onboarded() }
        val reader = members.onboarded()
        val written = CopyOnWriteArrayList<Long>()
        val leaked = CopyOnWriteArrayList<Long>()
        val writing = AtomicBoolean(true)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(AUTHORS + 1)
        try {
            val watcher =
                executor.submit {
                    start.await()
                    while (writing.get()) leaked += safety.feedPostIds(reader).filter { it in written }
                    // 마지막 글이 커밋된 뒤 한 번 더 본다
                    leaked += safety.feedPostIds(reader).filter { it in written }
                }
            val writers =
                authors.map { author ->
                    executor.submit(
                        Callable {
                            start.await()
                            repeat(POSTS_PER_AUTHOR) {
                                written += coreLoop.createPost(author, "죽고 싶다는 생각이 들어요 $it")
                            }
                        },
                    )
                }
            start.countDown()
            writers.forEach { it.get(60, TimeUnit.SECONDS) }
            writing.set(false)
            watcher.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertThat(written).hasSize(AUTHORS * POSTS_PER_AUTHOR)
        assertThat(leaked).isEmpty()
        val hidden =
            jdbcTemplate.queryForObject(
                "select count(*) from posts where id = any(?) and hidden_at is not null and hidden_reason = 'RISK'",
                Int::class.java,
                written.toTypedArray(),
            )
        assertThat(hidden).isEqualTo(AUTHORS * POSTS_PER_AUTHOR)
    }

    private companion object {
        const val AUTHORS = 10
        const val POSTS_PER_AUTHOR = 10
    }
}
