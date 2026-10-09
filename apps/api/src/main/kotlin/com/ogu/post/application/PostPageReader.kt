package com.ogu.post.application

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.PostOrder
import com.ogu.post.PostPage
import com.ogu.post.PostPageQuery
import com.ogu.post.domain.Visibility
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component

/**
 * 피드 한 쪽을 키셋으로 읽는다(research R7). 최신순은 `id DESC`, 인기순은 `(like_count DESC, id DESC)`이고, 둘 다
 * 보이는 글(지우지 않았고 숨기지 않은 글)의 부분 인덱스(posts_feed_latest_idx, posts_feed_popular_idx)를 탄다. 보는 회원의 공감 여부는
 * 같은 쿼리의 EXISTS로 읽어 피드 조립 쿼리 수를 4개로 고정한다. 다음 쪽이 있는지는 한 개 더 읽어 확인한다.
 */
@Component
class PostPageReader(
    private val jdbcClient: JdbcClient,
) {
    fun read(query: PostPageQuery): PostPage {
        val cursor = query.cursor?.let(PostCursor::decode)
        val params = mutableMapOf<String, Any>("viewerId" to query.viewerId, "limit" to query.size + 1)
        val conditions = mutableListOf(Visibility.visible("p"))
        if (query.jobRoles.isNotEmpty()) {
            conditions += "p.author_job_role in (:jobRoles)"
            params["jobRoles"] = query.jobRoles.map(JobRole::name)
        }
        if (query.careerYears.isNotEmpty()) {
            conditions += "p.author_career_year in (:careerYears)"
            params["careerYears"] = query.careerYears.map(CareerYear::name)
        }
        if (cursor != null) {
            conditions +=
                when (query.order) {
                    PostOrder.LATEST -> "p.id < :cursorId"
                    PostOrder.POPULAR -> "(p.like_count, p.id) < (:cursorLikeCount, :cursorId)"
                }
            params["cursorId"] = cursor.postId
            params["cursorLikeCount"] = cursor.likeCount
        }
        val orderBy =
            when (query.order) {
                PostOrder.LATEST -> "p.id desc"
                PostOrder.POPULAR -> "p.like_count desc, p.id desc"
            }

        val rows =
            jdbcClient
                .sql(
                    """
                    $SELECT
                    where ${conditions.joinToString(" and ")}
                    order by $orderBy
                    limit :limit
                    """.trimIndent(),
                ).params(params)
                .query { rs, _ -> rs.toPostPageItem() }
                .list()

        val items = rows.take(query.size)
        val nextCursor =
            if (rows.size > query.size) {
                items.last().post.let { PostCursor(it.likeCount, it.postId).encode() }
            } else {
                null
            }
        return PostPage(items, nextCursor)
    }

    private companion object {
        val SELECT =
            """
            select $POST_COLUMNS,
                   exists (select 1 from post_likes l where l.post_id = p.id and l.member_id = :viewerId) as liked_by_me
            from posts p
            """.trimIndent()
    }
}
