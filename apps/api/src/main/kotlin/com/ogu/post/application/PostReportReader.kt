package com.ogu.post.application

import com.ogu.post.PostReportApi
import com.ogu.post.ReceivedCounts
import com.ogu.post.ReportPostRef
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/** [PostReportApi]의 구현. 쿼리는 호출마다 한 번이다. */
@Service
@Transactional(readOnly = true)
class PostReportReader(
    private val jdbcClient: JdbcClient,
) : PostReportApi {
    override fun authorIdsBetween(
        from: Instant,
        until: Instant,
        afterAuthorId: Long,
        limit: Int,
    ): List<Long> =
        jdbcClient
            .sql(
                """
                select distinct p.author_id from posts p
                where p.created_at >= :from and p.created_at < :until and p.deleted_at is null
                  and p.author_id > :afterAuthorId
                order by p.author_id
                limit :limit
                """.trimIndent(),
            ).param("from", Timestamp.from(from))
            .param("until", Timestamp.from(until))
            .param("afterAuthorId", afterAuthorId)
            .param("limit", limit)
            .query { rs, _ -> rs.getLong("author_id") }
            .list()

    override fun postsBetween(
        authorId: Long,
        from: Instant,
        until: Instant,
    ): List<ReportPostRef> =
        jdbcClient
            .sql(
                """
                select p.id, p.created_at, p.risk_level from posts p
                where p.author_id = :authorId and p.created_at >= :from and p.created_at < :until
                  and p.deleted_at is null
                order by p.created_at, p.id
                """.trimIndent(),
            ).param("authorId", authorId)
            .param("from", Timestamp.from(from))
            .param("until", Timestamp.from(until))
            .query { rs, _ ->
                ReportPostRef(rs.getLong("id"), rs.getTimestamp("created_at").toInstant(), rs.getString("risk_level"))
            }.list()

    override fun receivedBetween(
        authorId: Long,
        from: Instant,
        until: Instant,
    ): ReceivedCounts =
        jdbcClient
            .sql(
                """
                select
                  (select count(*) from post_likes l join posts p on p.id = l.post_id
                   where p.author_id = :authorId and p.deleted_at is null and l.member_id <> :authorId
                     and l.created_at >= :from and l.created_at < :until) as likes,
                  (select count(*) from comments c join posts p on p.id = c.post_id
                   where p.author_id = :authorId and p.deleted_at is null and c.author_id <> :authorId
                     and c.deleted_at is null and c.hidden_at is null
                     and c.created_at >= :from and c.created_at < :until) as comments
                """.trimIndent(),
            ).param("authorId", authorId)
            .param("from", Timestamp.from(from))
            .param("until", Timestamp.from(until))
            .query { rs, _ -> ReceivedCounts(rs.getInt("likes"), rs.getInt("comments")) }
            .single()
}
