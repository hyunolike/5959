package com.ogu.safety.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant

/**
 * `safety_term`을 메모리에 올려 둔다(005 research R4). 글 저장 트랜잭션 안에서 불리므로 느리거나 실패하면 안 된다.
 * [SafetyProperties.termRefreshInterval]마다 행 수와 가장 늦은 `updated_at`만 읽어 바뀌었을 때만 목록을 다시 읽는다.
 * 읽지 못하면 마지막 목록을 그대로 쓰고, 한 번도 읽지 못했으면 [SafetyTerms.BUILT_IN]을 쓴다. 예외를 밖으로 던지지 않는다.
 */
@Component
class TermCache(
    private val jdbcClient: JdbcClient,
    private val properties: SafetyProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var terms: SafetyTerms = SafetyTerms.BUILT_IN

    @Volatile
    private var version: String? = null

    @Volatile
    private var checkedAt: Instant = Instant.MIN

    fun terms(): SafetyTerms {
        if (clock.instant() >= checkedAt.plusOrMax()) refresh()
        return terms
    }

    /** 바뀌었는지 보지 않고 지금 다시 읽는다. 운영자가 낱말을 고친 인스턴스와 테스트가 쓴다. */
    fun refreshNow() {
        version = null
        refresh()
    }

    @Synchronized
    private fun refresh() {
        checkedAt = clock.instant()
        runCatching {
            val current =
                jdbcClient
                    .sql("select count(*) || ':' || coalesce(max(updated_at)::text, '') from safety_term")
                    .query(String::class.java)
                    .single()
            if (current != version) {
                terms = load()
                version = current
            }
        }.onFailure {
            // 낱말을 읽지 못해도 판정은 마지막 목록으로 계속한다. 본문이나 낱말은 남기지 않는다
            log.warn("낱말 목록을 읽지 못해 마지막 목록으로 판정한다: {}", it.javaClass.simpleName)
        }
    }

    private fun load(): SafetyTerms {
        val byKind =
            jdbcClient
                .sql("select kind, term from safety_term")
                .query { rs, _ -> rs.getString("kind") to rs.getString("term") }
                .list()
                .groupBy({ it.first }, { it.second })
        return SafetyTerms(
            crisis = byKind["CRISIS"].orEmpty().toSet(),
            concern = byKind["CONCERN"].orEmpty().toSet(),
            profanity = byKind["PROFANITY"].orEmpty().toSet(),
            allow = byKind["ALLOW"].orEmpty().toSet(),
        )
    }

    private fun Instant.plusOrMax(): Instant {
        if (this == Instant.MIN) return this
        return plus(properties.termRefreshInterval)
    }
}
