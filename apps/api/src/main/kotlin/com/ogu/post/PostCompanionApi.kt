package com.ogu.post

import java.time.Instant

/**
 * AI 친구(companion 모듈)가 글의 상태를 읽고 댓글과 공감을 남기는 파사드(009 research R5). post는 AI를 모른다. 어느 회원이
 * AI 친구인지는 부르는 쪽이 [companionIds]로 넘긴다. 다른 모듈은 이 파사드를 쓰지 않는다.
 */
interface PostCompanionApi {
    /** 지우지 않은 글의 지금 상태. 지웠거나 없으면 null이다. 숨긴 글도 돌려준다([CompanionTarget.hidden]). */
    fun target(
        postId: Long,
        companionIds: Collection<Long>,
    ): CompanionTarget?

    /**
     * [memberId]의 이름으로 댓글 하나와 공감 하나를 한 트랜잭션에 남긴다. 사람이 쓰는 댓글 쓰기와 공감을 그대로 타므로
     * 글자 수 검사, 카운터, 이벤트(몬스터 HP, 알림, 위험 판정)가 같다. 댓글과 공감은 함께 남거나 함께 남지 않는다.
     *
     * 남기기 전에 글의 행을 잠그고 다시 본다. 글이 사라졌으면 [CompanionReactionOutcome.GONE], [companionIds] 가운데
     * 누가 이미 댓글을 달았으면 [CompanionReactionOutcome.ALREADY_REACTED], 그 사이 다른 회원이 댓글을 달았으면
     * [CompanionReactionOutcome.HUMAN_COMMENTED]다. 댓글이 규칙에 맞지 않거나 공감이 실패하면 사람의 요청과 같은 예외가
     * 나가고 아무것도 남지 않는다.
     */
    fun react(
        postId: Long,
        memberId: Long,
        content: String,
        companionIds: Collection<Long>,
    ): CompanionReactionResult
}

/** AI 친구가 반응할지 정하는 데 쓰는 글 하나. [content]는 가리지 않은 원문이다. */
data class CompanionTarget(
    val postId: Long,
    val authorId: Long,
    val content: String,
    val commentTone: CommentTone,
    val createdAt: Instant,
    /** 다른 회원에게 보이지 않는 글이면 true. */
    val hidden: Boolean,
    /** AI 친구가 이 글에 이미 남긴 댓글(지우지 않은 것). 없으면 null이다. */
    val companionCommentId: Long?,
    /** 글쓴이도 AI 친구도 아닌 회원의, 지우지도 숨기지도 않은 댓글이 있다. */
    val otherCommented: Boolean,
)

enum class CompanionReactionOutcome {
    REACTED,
    ALREADY_REACTED,
    GONE,
    HUMAN_COMMENTED,
}

/** [commentId]는 [CompanionReactionOutcome.REACTED]와 [CompanionReactionOutcome.ALREADY_REACTED]일 때만 있다. */
data class CompanionReactionResult(
    val outcome: CompanionReactionOutcome,
    val commentId: Long? = null,
)
