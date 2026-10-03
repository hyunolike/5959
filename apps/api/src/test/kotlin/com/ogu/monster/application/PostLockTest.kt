package com.ogu.monster.application

import com.ogu.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * T013: 글 단위 잠금(research R5). HP 반영과 몬스터 생성이 같은 글에서 겹치지 않도록 현재 트랜잭션에
 * `pg_advisory_xact_lock(postId)`을 잡고, 트랜잭션이 끝나면 풀린다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class PostLockTest {
    @Autowired
    lateinit var postLock: PostLock

    @Autowired
    lateinit var transactionTemplate: TransactionTemplate

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `트랜잭션 밖에서 부르면 잠그지 않고 실패한다`() {
        assertThatThrownBy { postLock.lock(POST_ID) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `트랜잭션 안에서 잡은 잠금은 다른 트랜잭션이 잡지 못하고 커밋하면 풀린다`() {
        val postId = POST_ID + 1
        transactionTemplate.executeWithoutResult {
            postLock.lock(postId)

            assertThat(tryLockFromAnotherConnection(postId)).isFalse()
        }

        assertThat(tryLockFromAnotherConnection(postId)).isTrue()
    }

    @Test
    fun `다른 글의 잠금과는 서로 막지 않는다`() {
        transactionTemplate.executeWithoutResult {
            postLock.lock(POST_ID + 2)

            assertThat(tryLockFromAnotherConnection(POST_ID + 3)).isTrue()
        }
    }

    /** 다른 스레드(다른 커넥션, 다른 트랜잭션)에서 같은 키를 바로 잡을 수 있는지 본다. */
    private fun tryLockFromAnotherConnection(postId: Long): Boolean {
        val executor = Executors.newSingleThreadExecutor()
        try {
            return executor
                .submit<Boolean> {
                    transactionTemplate.execute {
                        jdbcTemplate.queryForObject(
                            "select pg_try_advisory_xact_lock(?)",
                            Boolean::class.java,
                            postId,
                        )
                    }
                }.get(10, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    companion object {
        private const val POST_ID = 8_100_000_000L
    }
}
