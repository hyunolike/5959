package com.ogu.safety.application

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock

/**
 * 안전 기록의 보관 기간 정리(005 research R13). 매일 04:30(한국 시간)에 만든 지 [SafetyProperties.retention](1년)이 지난
 * 위험 판정, 신고, 재검토 요청, 운영자 처리 기록을 [SafetyProperties.purgeBatchSize](1,000)행씩 지운다.
 *
 * - 글과 댓글의 숨김 상태와 단계는 `posts`, `comments`의 열이라 그대로 남는다. 기록만 지운다.
 * - 아직 열려 있는 신고와 재검토 요청은 지우지 않는다. 처리되지 않은 일이 조용히 사라지면 안 된다.
 * - 한 문장이 한 묶음만 지우고 바로 커밋한다. 인스턴스가 둘이어도 다른 쪽이 잡은 행은 건너뛴다(`SKIP LOCKED`).
 */
@Component
class SafetyPurgeJob(
    private val jdbcClient: JdbcClient,
    private val properties: SafetyProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${ogu.safety.purge-cron:0 30 4 * * *}", zone = "Asia/Seoul")
    fun purge(): Map<String, Int> {
        val cutoff = Timestamp.from(clock.instant().minus(properties.retention))
        val deleted = TABLES.associate { (table, condition) -> table to purgeTable(table, condition, cutoff) }
        log.info("Deleted expired safety records: {}", deleted)
        return deleted
    }

    private fun purgeTable(
        table: String,
        condition: String,
        cutoff: Timestamp,
    ): Int {
        val batchSize = properties.purgeBatchSize
        var deleted = 0
        do {
            val count =
                jdbcClient
                    .sql(
                        """
                        delete from $table where id in (
                            select id from $table
                            where created_at < :cutoff and $condition
                            order by id
                            limit :limit
                            for update skip locked
                        )
                        """.trimIndent(),
                    ).param("cutoff", cutoff)
                    .param("limit", batchSize)
                    .update()
            deleted += count
        } while (count >= batchSize)
        return deleted
    }

    private companion object {
        const val CLOSED = "status <> 'PENDING'"

        /** 테이블과, 지워도 되는 행의 조건. */
        val TABLES =
            listOf(
                "risk_assessment" to CLOSED,
                "report" to CLOSED,
                "review_request" to CLOSED,
                "moderation_action" to "true",
            )
    }
}
