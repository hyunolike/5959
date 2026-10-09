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
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * 고민 글(data-model.md `posts`). 본문은 앞뒤 공백을 빼고 사람이 보는 글자 기준 1~500자다(FR-001, research R8).
 * 시각은 주입한 Clock으로 정한다(작성 제한 R9가 같은 시계로 최근 1시간을 센다).
 * 공감 수와 댓글 수는 원자적 UPDATE로만 바꾸므로, 수정할 때 바뀐 열만 UPDATE하도록 [DynamicUpdate]를 쓴다(읽어 둔 낡은
 * 카운터로 덮어쓰지 않게). 삭제도 `PostRepository.softDelete` 한 문장으로 한다.
 */
@Entity
@DynamicUpdate
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

    @Column(name = "content", nullable = false, columnDefinition = "text")
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

    /** 다른 회원에게 보이지 않게 된 시각(005 research R5). 작성자에게는 계속 보인다. safety 모듈이 파사드로만 바꾼다. */
    @Column(name = "hidden_at", insertable = false, updatable = false)
    var hiddenAt: Instant? = null
        protected set

    /** 가장 최근 위험 판정의 단계(`NONE`, `CONCERN`, `CRISIS`). 작성자에게만 응답에 실린다(005 research R7). */
    @Column(name = "risk_level", nullable = false, insertable = false, updatable = false, length = 10)
    var riskLevel: String = RISK_NONE
        protected set

    @Column(name = "review_requested_at", insertable = false, updatable = false)
    var reviewRequestedAt: Instant? = null
        protected set

    val isDeleted: Boolean
        get() = deletedAt != null

    val isHidden: Boolean
        get() = hiddenAt != null

    /**
     * 본문이나 댓글 말투를 고친다(US4-AC1, FR-013). null인 쪽은 그대로 둔다. 본문 규칙은 작성과 같다([normalizeContent]).
     * 몬스터는 다시 분석하지 않는다.
     */
    fun edit(
        content: String?,
        commentTone: CommentTone?,
        now: Instant,
    ) {
        content?.let { this.content = normalizeContent(it) }
        commentTone?.let { this.commentTone = it }
        updatedAt = now
    }

    companion object {
        const val CONTENT_MAX_LENGTH = 500
        const val RISK_NONE = "NONE"
        const val CONTENT_MAX_CODE_POINTS = 5000

        fun write(
            author: PostAuthor,
            content: String,
            commentTone: CommentTone,
            now: Instant,
        ): Post = Post(author.id, author.jobRole, author.careerYear, normalizeContent(content), commentTone, now)

        /** 앞뒤 공백을 빼고 1~500자인지 검사한다. 어기면 400 INVALID_REQUEST. */
        fun normalizeContent(raw: String): String {
            val content = raw.trim()
            // 싼 검사부터 한다. 코드 포인트가 상한을 넘으면 글자 분할은 상한 바로 위까지만 한다. 메시지는 글자 수가
            // 넘쳤는지에 따라 가르므로, 검사 순서를 바꿔도 오류 코드와 메시지는 그대로다.
            // 남용 방지(research R8): 결합 문자를 겹겹이 쌓은 글(Zalgo)은 글자 수는 적어도 코드 포인트가 매우 많다.
            // LLM에 보내는 양도 이 상한으로 막는다.
            if (content.codePointCount(0, content.length) > CONTENT_MAX_CODE_POINTS) {
                val tooLong = Grapheme.count(content, limit = CONTENT_MAX_LENGTH + 1) > CONTENT_MAX_LENGTH
                throw if (tooLong) tooLong() else BusinessException(ErrorCode.INVALID_REQUEST, "본문에 결합 문자가 너무 많습니다.")
            }
            if (Grapheme.count(content) !in 1..CONTENT_MAX_LENGTH) throw tooLong()
            return content
        }

        private fun tooLong() =
            BusinessException(
                ErrorCode.INVALID_REQUEST,
                "본문은 앞뒤 공백을 뺀 1자 이상 ${CONTENT_MAX_LENGTH}자 이하여야 합니다.",
            )
    }
}

/** 글을 쓰는 회원과 그때의 직군, 경력(스냅숏, research R7). */
data class PostAuthor(
    val id: Long,
    val jobRole: JobRole,
    val careerYear: CareerYear,
)
