package com.ogu.post.domain

import com.ogu.shared.error.BusinessException
import com.ogu.shared.error.ErrorCode
import com.ogu.shared.text.Grapheme
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.DynamicUpdate
import java.time.Instant

/**
 * 댓글과 답글(data-model.md `comments`, FR-008). 답글은 [parentId]에 원 댓글을 가리키고, 답글에는 다시 답글을 달 수 없다.
 * 본문은 앞뒤 공백을 빼고 사람이 보는 글자 기준 1~300자다(research R8). 공감 수는 원자적 UPDATE로만 바꾸므로, 수정할 때
 * 바뀐 열만 UPDATE하도록 [DynamicUpdate]를 쓴다.
 */
@Entity
@DynamicUpdate
@Table(name = "comments")
class Comment private constructor(
    postId: Long,
    authorId: Long,
    parentId: Long?,
    content: String,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0L
        protected set

    @Column(name = "post_id", nullable = false, updatable = false)
    var postId: Long = postId
        protected set

    @Column(name = "author_id", nullable = false, updatable = false)
    var authorId: Long = authorId
        protected set

    @Column(name = "parent_id", updatable = false)
    var parentId: Long? = parentId
        protected set

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String = content
        protected set

    @Column(name = "like_count", nullable = false)
    var likeCount: Int = 0
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

    /** 다른 회원에게 보이지 않게 된 시각(005 research R5). 자리는 남고 내용과 작성자만 가린다. */
    @Column(name = "hidden_at", insertable = false, updatable = false)
    var hiddenAt: Instant? = null
        protected set

    @Column(name = "risk_level", nullable = false, insertable = false, updatable = false, length = 10)
    var riskLevel: String = "NONE"
        protected set

    @Column(name = "review_requested_at", insertable = false, updatable = false)
    var reviewRequestedAt: Instant? = null
        protected set

    val isReply: Boolean
        get() = parentId != null

    val isHidden: Boolean
        get() = hiddenAt != null

    /** 본문을 고친다(US4-AC3, FR-014). 규칙은 작성과 같다([normalizeContent]). */
    fun edit(
        content: String,
        now: Instant,
    ) {
        this.content = normalizeContent(content)
        updatedAt = now
    }

    companion object {
        const val CONTENT_MAX_LENGTH = 300
        const val CONTENT_MAX_CODE_POINTS = 3000

        /** [parent]가 있으면 그 원 댓글의 답글이다. 부모 검증(같은 글, 살아 있음, 원 댓글)은 부르는 쪽이 한다. */
        fun write(
            postId: Long,
            authorId: Long,
            parent: Comment?,
            content: String,
            now: Instant,
        ): Comment = Comment(postId, authorId, parent?.id, normalizeContent(content), now)

        /** 앞뒤 공백을 빼고 1~300자인지 검사한다. 어기면 400 INVALID_REQUEST. */
        fun normalizeContent(raw: String): String {
            val content = raw.trim()
            // 싼 검사부터 한다. 코드 포인트가 상한을 넘으면 글자 분할은 상한 바로 위까지만 한다. 메시지는 글자 수가
            // 넘쳤는지에 따라 가르므로, 검사 순서를 바꿔도 오류 코드와 메시지는 그대로다.
            // 남용 방지(research R8): 결합 문자를 겹겹이 쌓은 글은 글자 수가 적어도 코드 포인트가 매우 많다.
            if (content.codePointCount(0, content.length) > CONTENT_MAX_CODE_POINTS) {
                val tooLong = Grapheme.count(content, limit = CONTENT_MAX_LENGTH + 1) > CONTENT_MAX_LENGTH
                throw if (tooLong) tooLong() else BusinessException(ErrorCode.INVALID_REQUEST, "댓글에 결합 문자가 너무 많습니다.")
            }
            if (Grapheme.count(content) !in 1..CONTENT_MAX_LENGTH) throw tooLong()
            return content
        }

        private fun tooLong() =
            BusinessException(
                ErrorCode.INVALID_REQUEST,
                "댓글은 앞뒤 공백을 뺀 1자 이상 ${CONTENT_MAX_LENGTH}자 이하여야 합니다.",
            )
    }
}
