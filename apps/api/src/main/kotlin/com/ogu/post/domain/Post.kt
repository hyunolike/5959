package com.ogu.post.domain

import com.ogu.member.CareerYear
import com.ogu.member.JobRole
import com.ogu.post.CommentTone
import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.text.Grapheme
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 고민 글(data-model.md `posts`). 본문은 앞뒤 공백을 빼고 사람이 보는 글자 기준 1~500자다(FR-001, research R8).
 * 시각은 주입한 Clock으로 정한다(작성 제한 R9가 같은 시계로 최근 1시간을 센다).
 */
@Entity
@Table(name = "posts")
class Post private constructor(
    authorId: Long,
    authorJobRole: JobRole,
    authorCareerYear: CareerYear,
    content: String,
    commentTone: CommentTone,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0L
        protected set

    @Column(name = "author_id", nullable = false, updatable = false)
    var authorId: Long = authorId
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "author_job_role", nullable = false, length = 20)
    var authorJobRole: JobRole = authorJobRole
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "author_career_year", nullable = false, length = 20)
    var authorCareerYear: CareerYear = authorCareerYear
        protected set

    @Column(name = "content", nullable = false, length = 2000)
    var content: String = content
        protected set

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_tone", nullable = false, length = 20)
    var commentTone: CommentTone = commentTone
        protected set

    @Column(name = "like_count", nullable = false)
    var likeCount: Int = 0
        protected set

    @Column(name = "comment_count", nullable = false)
    var commentCount: Int = 0
        protected set

    @Column(name = "deleted_at")
    var deletedAt: Instant? = null
        protected set

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = createdAt
        protected set

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = createdAt
        protected set

    val isDeleted: Boolean
        get() = deletedAt != null

    companion object {
        const val CONTENT_MAX_LENGTH = 500
        private const val STORAGE_MAX_CODE_POINTS = 2000

        fun write(
            author: PostAuthor,
            content: String,
            commentTone: CommentTone,
            now: Instant,
        ): Post = Post(author.id, author.jobRole, author.careerYear, normalizeContent(content), commentTone, now)

        /** 앞뒤 공백을 빼고 1~500자인지 검사한다. 어기면 400 INVALID_REQUEST. */
        fun normalizeContent(raw: String): String {
            val content = raw.trim()
            val length = Grapheme.count(content)
            if (length !in 1..CONTENT_MAX_LENGTH) {
                throw BusinessException(
                    ErrorCode.INVALID_REQUEST,
                    "본문은 앞뒤 공백을 뺀 1자 이상 ${CONTENT_MAX_LENGTH}자 이하여야 합니다.",
                )
            }
            // 결합 이모지는 1자라도 코드 포인트가 여러 개라, 500자 안에서도 컬럼(varchar(2000), 코드 포인트 기준)을 넘을 수 있다.
            if (content.codePointCount(0, content.length) > STORAGE_MAX_CODE_POINTS) {
                throw BusinessException(ErrorCode.INVALID_REQUEST, "본문에 결합 이모지가 너무 많습니다.")
            }
            return content
        }
    }
}

/** 글을 쓰는 회원과 그때의 직군, 경력(스냅숏, research R7). */
data class PostAuthor(
    val id: Long,
    val jobRole: JobRole,
    val careerYear: CareerYear,
)
