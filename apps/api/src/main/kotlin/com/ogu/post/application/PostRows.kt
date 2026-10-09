package com.ogu.post.application

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.CommentTone
import com.ogu.post.PostPageItem
import com.ogu.post.PostPageQuery
import com.ogu.post.PostSummary
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import java.sql.ResultSet

/** 피드와 마이페이지의 글 목록이 함께 읽는 `posts p`의 열. [toPostPageItem]과 짝이다. */
internal const val POST_COLUMNS =
    "p.id, p.author_id, p.author_job_role, p.author_career_year, p.content, p.comment_tone, " +
        "p.like_count, p.comment_count, p.created_at, p.hidden_at, p.risk_level, p.review_requested_at"

/** 알림과 내 댓글에 붙이는 글 미리보기 글자 수(사람이 보는 글자 단위). */
internal const val POST_PREVIEW_LENGTH = 50

/** [POST_COLUMNS]와 `liked_by_me` 열을 읽은 행. */
internal fun ResultSet.toPostPageItem(): PostPageItem =
    PostPageItem(
        post =
            PostSummary(
                postId = getLong("id"),
                authorId = getLong("author_id"),
                authorJobRole = JobRole.valueOf(getString("author_job_role")),
                authorCareerYear = CareerYear.valueOf(getString("author_career_year")),
                content = getString("content"),
                commentTone = CommentTone.valueOf(getString("comment_tone")),
                likeCount = getInt("like_count"),
                commentCount = getInt("comment_count"),
                createdAt = getTimestamp("created_at").toInstant(),
                hidden = getTimestamp("hidden_at") != null,
                riskLevel = getString("risk_level"),
                reviewRequested = getTimestamp("review_requested_at") != null,
            ),
        likedByMe = getBoolean("liked_by_me"),
    )

/** 마이페이지 목록의 쪽 크기. 피드와 같은 범위이고, 벗어나면 400 INVALID_REQUEST. */
internal fun requirePageSize(size: Int) {
    if (size !in 1..PostPageQuery.MAX_SIZE) {
        throw BusinessException(ErrorCode.INVALID_REQUEST, "size는 1 이상 ${PostPageQuery.MAX_SIZE} 이하여야 합니다.")
    }
}
