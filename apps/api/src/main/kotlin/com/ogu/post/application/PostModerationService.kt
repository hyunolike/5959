package com.ogu.post.application

import com.ogu.post.ContentType
import com.ogu.post.ModerationTarget
import com.ogu.post.PostModerationApi
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * [PostModerationApi]의 구현. 숨김과 단계 열은 여기서만 바꾼다. 모든 문장이 JdbcClient라 JPA가 아직 내보내지 않은 변경은
 * 보이지 않는다. 그래서 글과 댓글을 고친 쪽이 이벤트를 내기 전에 flush한다(PostService, CommentService).
 */
@Service
class PostModerationService(
    private val jdbcClient: JdbcClient,
    private val clock: Clock,
) : PostModerationApi {
    @Transactional(readOnly = true)
    override fun contentOf(
        type: ContentType,
        id: Long,
    ): ModerationTarget? =
        jdbcClient
            .sql("${select(type)} and t.id = :id")
            .param("id", id)
            .query { rs, _ -> rs.toTarget(type) }
            .optional()
            .orElse(null)

    @Transactional(readOnly = true)
    override fun contentsOf(targets: Collection<TargetKey>): Map<TargetKey, ModerationTarget> =
        targets
            .groupBy({ it.first }, { it.second })
            .flatMap { (type, ids) ->
                jdbcClient
                    .sql("${select(type)} and t.id in (:ids)")
                    .param("ids", ids.toSet())
                    .query { rs, _ -> rs.toTarget(type) }
                    .list()
            }.associateBy { it.type to it.id }

    @Transactional
    override fun markRisk(
        type: ContentType,
        id: Long,
        level: String,
    ) {
        jdbcClient
            .sql("update ${table(type)} set risk_level = :level where id = :id")
            .param("level", level)
            .param("id", id)
            .update()
    }

    @Transactional
    override fun markReviewRequested(
        type: ContentType,
        id: Long,
    ) {
        jdbcClient
            .sql("update ${table(type)} set review_requested_at = :now where id = :id and review_requested_at is null")
            .param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
            .param("id", id)
            .update()
    }

    @Transactional
    override fun hide(
        type: ContentType,
        id: Long,
        reason: String,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update ${table(type)} set hidden_at = :now, hidden_reason = :reason
                where id = :id and deleted_at is null and hidden_at is null
                """.trimIndent(),
            ).param("now", Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)))
            .param("reason", reason)
            .param("id", id)
            .update() == 1

    @Transactional
    override fun unhide(
        type: ContentType,
        id: Long,
    ): Boolean =
        jdbcClient
            .sql(
                """
                update ${table(type)} set hidden_at = null, hidden_reason = null, review_requested_at = null
                where id = :id and deleted_at is null and hidden_at is not null
                """.trimIndent(),
            ).param("id", id)
            .update() == 1

    @Transactional(readOnly = true)
    override fun scan(
        type: ContentType,
        afterId: Long,
        limit: Int,
    ): List<ModerationTarget> =
        jdbcClient
            .sql("${select(type)} and t.id > :afterId order by t.id limit :limit")
            .param("afterId", afterId)
            .param("limit", limit)
            .query { rs, _ -> rs.toTarget(type) }
            .list()
}

private typealias TargetKey = Pair<ContentType, Long>

private fun table(type: ContentType): String =
    when (type) {
        ContentType.POST -> "posts"
        ContentType.COMMENT -> "comments"
    }

/** 지우지 않은 대상. 댓글은 그 글도 지우지 않았어야 한다. 뒤에 `and t.id ...` 조건을 붙여 쓴다. */
private fun select(type: ContentType): String =
    when (type) {
        ContentType.POST ->
            """
            select t.id, t.id as post_id, t.author_id, t.content, t.updated_at, t.hidden_at
            from posts t
            where t.deleted_at is null
            """.trimIndent()

        ContentType.COMMENT ->
            """
            select t.id, t.post_id, t.author_id, t.content, t.updated_at, t.hidden_at
            from comments t
                join posts p on p.id = t.post_id and p.deleted_at is null
            where t.deleted_at is null
            """.trimIndent()
    }

private fun ResultSet.toTarget(type: ContentType): ModerationTarget =
    ModerationTarget(
        type = type,
        id = getLong("id"),
        postId = getLong("post_id"),
        authorId = getLong("author_id"),
        content = getString("content"),
        updatedAt = getTimestamp("updated_at").toInstant(),
        hidden = getTimestamp("hidden_at") != null,
    )
