package com.ogu.companion

import com.ogu.companion.application.CompanionProperties
import com.ogu.companion.application.CompanionProperties.Posting
import com.ogu.companion.application.PostComposer
import com.ogu.companion.application.PostingPlanner
import com.ogu.companion.application.PostingRunner
import com.ogu.companion.application.PostingStore
import com.ogu.companion.domain.CompanionPostRepository
import com.ogu.companion.domain.CompanionRepository
import com.ogu.post.CompanionPostApi
import com.ogu.shared.config.AiProperties
import com.ogu.support.ScriptedCompanionPostWriter
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant

/**
 * AI 친구의 글쓰기를 시계를 움직이며 확인하는 테스트의 바탕(012 research R20). 주기 작업을 끄고 실행기를 직접 부른다.
 * 테스트마다 아직 아무도 글을 쓰지 않은 날의 아침으로 시계를 옮긴다. 컨텍스트들이 DB를 함께 써서, 그러지 않으면 다른
 * 테스트의 글 때문에 "한동안 새 글이 없었다"가 되지 않는다.
 */
abstract class CompanionPostingTestBase : CompanionTestBase() {
    @Autowired
    lateinit var postingRunner: PostingRunner

    @Autowired
    lateinit var postWriter: ScriptedCompanionPostWriter

    @Autowired
    lateinit var attempts: CompanionPostRepository

    @Autowired
    lateinit var friends: CompanionRepository

    @Autowired
    lateinit var companionPostApi: CompanionPostApi

    @Autowired
    lateinit var postComposer: PostComposer

    @Autowired
    lateinit var properties: CompanionProperties

    @Autowired
    lateinit var aiProperties: AiProperties

    /** 이 테스트가 시작한 때. 이 뒤에 생긴 글과 기록만 본다. */
    protected lateinit var started: Instant

    @BeforeEach
    fun startOnQuietMorning() {
        postWriter.reset()
        companions.quietDay()
        started = clock.instant()
    }

    @AfterEach
    fun leaveNothingWriting() {
        postWriter.reset()
        // 쓰는 중인 기록은 하나뿐이라, 남겨 두면 뒤 테스트가 새 기록을 열지 못한다
        jdbcTemplate.update(
            "update companion_post set status = 'GIVEN_UP', give_up_reason = 'EXPIRED', next_attempt_at = null, " +
                "completed_at = now() where status = 'WRITING'",
        )
    }

    /** 글쓰기 설정만 바꾼 실행기. 컨텍스트를 하나 더 띄우지 않고 조용한 시간이나 하루의 수를 바꿔 본다. */
    protected fun runnerWith(change: (Posting) -> Posting): PostingRunner {
        val changed = properties.copy(posting = change(properties.posting))
        val store = PostingStore(attempts, changed, clock, aiProperties)
        val planner = PostingPlanner(attempts, friends, companionPostApi, changed, clock)
        return PostingRunner(store, planner, postComposer, friends, companionPostApi)
    }

    /** 조용한 시간을 1분으로 줄인 실행기. 같은 날에 글 여럿을 쓰게 할 때 쓴다. */
    protected fun eagerRunner(change: (Posting) -> Posting = { it }): PostingRunner {
        val eager: (Posting) -> Posting = { change(it.copy(quiet = EAGER_QUIET)) }
        return runnerWith(eager)
    }

    /** 조용한 시간이 1분인 실행기가 다음 글을 쓸 수 있게 시간을 보낸다. */
    protected fun passEagerQuiet() = companions.pass(EAGER_QUIET.plusSeconds(1))

    /** 실패한 시도 뒤 다시 맡을 수 있게 시간을 보낸다. */
    protected fun passRetry() = companions.pass(Duration.ofMinutes(6))

    protected fun records(): List<Map<String, Any?>> = companions.postAttempts(started)

    protected fun aiPosts(): List<Map<String, Any?>> = companions.aiPosts(started)

    protected fun memberIdOf(record: Map<String, Any?>): Long =
        companions.memberIdByCompanion.getValue((record["companion_id"] as Number).toInt())

    protected companion object {
        val EAGER_QUIET: Duration = Duration.ofMinutes(1)
        val QUIET: Duration = Duration.ofHours(6)
    }
}
