package com.ogu.recommend

import com.ogu.TestcontainersConfiguration
import com.ogu.recommend.application.EmbeddingBackfill
import com.ogu.support.FutureClockSchedulers
import com.ogu.support.MemberFixture
import com.ogu.support.RecommendFixture
import com.ogu.support.SafetyFixture
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

/**
 * T023(018 research R3, data-model 1): 기동 때의 훑기는 모델마다 한 번이다. 서버가 아무 때나 다시 떠도 끝난 모델은
 * 모든 글을 다시 훑지 않는다. [EmbeddingPipelineTests]와 같은 컨텍스트를 쓴다(기동 때 훑기와 재시도 주기는 꺼져 있다).
 */
@SpringBootTest(
    properties = [
        FutureClockSchedulers.RECOMMEND,
        FutureClockSchedulers.RECOMMEND_BACKFILL,
        FutureClockSchedulers.RAID,
        FutureClockSchedulers.EMOTION,
        FutureClockSchedulers.SAFETY,
        FutureClockSchedulers.REPORT,
    ],
)
@Import(TestcontainersConfiguration::class, EmbeddingPipelineTests.ClockOverride::class)
class EmbeddingBackfillTests {
    @Autowired
    lateinit var context: WebApplicationContext

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    lateinit var backfill: EmbeddingBackfill

    private lateinit var members: MemberFixture
    private lateinit var safety: SafetyFixture
    private lateinit var recommend: RecommendFixture

    @BeforeEach
    fun setUp() {
        val mockMvc = MockMvcBuilders.webAppContextSetup(context).apply<DefaultMockMvcBuilder>(springSecurity()).build()
        members = MemberFixture(mockMvc)
        safety = SafetyFixture(mockMvc, jdbcTemplate)
        recommend = RecommendFixture(jdbcTemplate)
    }

    @Test
    fun `다시 떠도 끝난 모델은 훑지 않는다`() {
        markCompleted(MODEL)
        val postId = safety.insertPost(members.onboarded(), "표지가 있으면 올라가지 않을 글 ${recommend.topic()}")

        assertThat(backfill.runUnlessCompleted()).isNull()

        assertThat(recommend.row(postId)).isNull()
    }

    @Test
    fun `모델을 바꾸면 한 번 훑는다`() {
        // 옛 모델로는 마쳤고, 지금 모델로는 아직 훑지 않았다
        jdbcTemplate.update("delete from embedding_backfill where model = ?", MODEL)
        markCompleted(OLD_MODEL)
        val postId = safety.insertPost(members.onboarded(), "모델을 바꾼 뒤 올라갈 글 ${recommend.topic()}")

        assertThat(backfill.runUnlessCompleted()).isNotNull().isGreaterThanOrEqualTo(1)
        assertThat(recommend.status(postId)).isEqualTo("PENDING")
        assertThat(completedModels()).contains(MODEL, OLD_MODEL)

        // 마친 뒤 다시 떠도 훑지 않는다
        val later = safety.insertPost(members.onboarded(), "다시 뜬 뒤의 글 ${recommend.topic()}")
        assertThat(backfill.runUnlessCompleted()).isNull()
        assertThat(recommend.row(later)).isNull()
    }

    private fun markCompleted(model: String) {
        jdbcTemplate.update(
            "insert into embedding_backfill (model, completed_at) values (?, now()) on conflict (model) do nothing",
            model,
        )
    }

    private fun completedModels(): List<String> {
        val sql = "select model from embedding_backfill"
        return jdbcTemplate.queryForList(sql, String::class.java)
    }

    private companion object {
        const val MODEL = "fake-embedder"
        const val OLD_MODEL = "old-model"
    }
}
