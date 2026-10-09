package com.ogu.safety.domain

import com.ogu.post.ContentType
import org.springframework.jdbc.core.RowCallbackHandler
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** `report` 저장소(005 data-model.md). 같은 회원은 같은 대상을 한 번만 신고한다. */
@Repository
class ReportRepository(
    private val jdbcClient: JdbcClient,
) {
    /** 신고를 넣는다. 이미 신고한 대상이면 아무것도 하지 않고 null을 돌려준다(`report_reporter_target_key`). */
    fun insertIfAbsent(report: NewReport): Long? =
        jdbcClient
            .sql(
                """
                insert into report (reporter_id, target_type, target_id, post_id, reason, detail, status, created_at)
                values (:reporterId, :type, :targetId, :postId, :reason, :detail, 'PENDING', :now)
                on conflict on constraint report_reporter_target_key do nothing
                returning id
                """.trimIndent(),
            ).param("reporterId", report.reporterId)
            .param("type", report.type.name)
            .param("targetId", report.targetId)
            .param("postId", report.postId)
            .param("reason", report.reason.name)
            .param("detail", report.detail)
            .param("now", Timestamp.from(report.createdAt))
            .query(Long::class.java)
            .optional()
            .orElse(null)

    /** 같은 회원의 동시 신고가 한도를 함께 통과하지 않도록 회원 단위 advisory lock을 잡는다(트랜잭션이 끝나면 풀린다). */
    fun lockReporter(reporterId: Long) {
        jdbcClient
            .sql("select pg_advisory_xact_lock(:namespace, :key)")
            .param("namespace", LOCK_NAMESPACE)
            .param("key", (reporterId xor (reporterId ushr Int.SIZE_BITS)).toInt())
            .query(RowCallbackHandler { })
    }

    fun countSince(
        reporterId: Long,
        since: Instant,
    ): Int =
        jdbcClient
            .sql("select count(*) from report where reporter_id = :reporterId and created_at > :since")
            .param("reporterId", reporterId)
            .param("since", Timestamp.from(since))
            .query(Int::class.java)
            .single()

    /** [since] 뒤에 낸 신고 가운데 가장 이른 것의 시각. 한도가 풀리는 때를 알려 주는 데 쓴다. */
    fun oldestSince(
        reporterId: Long,
        since: Instant,
    ): Instant? =
        jdbcClient
            .sql("select min(created_at) from report where reporter_id = :reporterId and created_at > :since")
            .param("reporterId", reporterId)
            .param("since", Timestamp.from(since))
            .query(Timestamp::class.java)
            .optional()
            .map { it.toInstant() }
            .orElse(null)

    /** 대상의 열린 신고를 [status]로 닫고 닫은 수를 돌려준다. */
    fun closeOpenByTarget(
        type: ContentType,
        targetId: Long,
        status: String,
        now: Instant,
    ): Int =
        jdbcClient
            .sql(
                """
                update report set status = :status, closed_at = :now
                where target_type = :type and target_id = :targetId and status = 'PENDING'
                """.trimIndent(),
            ).param("status", status)
            .param("now", Timestamp.from(now))
            .param("type", type.name)
            .param("targetId", targetId)
            .update()

    /** 지워진 글에 달린 댓글들의 열린 신고도 함께 닫는다. */
    fun closeOpenByPost(
        postId: Long,
        status: String,
        now: Instant,
    ): Int =
        jdbcClient
            .sql("update report set status = :status, closed_at = :now where post_id = :postId and status = 'PENDING'")
            .param("status", status)
            .param("now", Timestamp.from(now))
            .param("postId", postId)
            .update()

    /** 신고의 대상. 없는 신고면 null이다. */
    fun targetOf(id: Long): Pair<ContentType, Long>? =
        jdbcClient
            .sql("select target_type, target_id from report where id = :id")
            .param("id", id)
            .query { rs, _ -> ContentType.valueOf(rs.getString("target_type")) to rs.getLong("target_id") }
            .optional()
            .orElse(null)

    /** 열린 신고 하나를 [status]로 닫는다. 이미 닫혀 있으면 false다. */
    fun close(
        id: Long,
        status: String,
        now: Instant,
    ): Boolean =
        jdbcClient
            .sql("update report set status = :status, closed_at = :now where id = :id and status = 'PENDING'")
            .param("status", status)
            .param("now", Timestamp.from(now))
            .param("id", id)
            .update() == 1

    private companion object {
        /** advisory lock 두 정수 키 중 첫째. 신고 제한 전용 값이다(글 작성 제한, 글 잠금과 겹치지 않는다). */
        const val LOCK_NAMESPACE = 0x72707274 // "rprt"
    }
}

enum class ReportReason {
    DANGEROUS,
    ABUSIVE,
    SPAM,
    OTHER,
}

data class NewReport(
    val reporterId: Long,
    val type: ContentType,
    val targetId: Long,
    val postId: Long,
    val reason: ReportReason,
    val detail: String?,
    val createdAt: Instant,
)
