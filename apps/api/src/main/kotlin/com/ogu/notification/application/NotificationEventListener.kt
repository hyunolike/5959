package com.ogu.notification.application

import com.ogu.monster.MonsterApi
import com.ogu.monster.MonsterDefeated
import com.ogu.monster.MonsterSpawned
import com.ogu.notification.domain.NotificationType
import com.ogu.post.CommentCreated
import com.ogu.post.CommentSummary
import com.ogu.post.ContentType
import com.ogu.post.PostApi
import com.ogu.post.PostLiked
import com.ogu.safety.RiskDetected
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 도메인 이벤트를 알림으로 만든다(data-model.md "알림 생성 규칙", research R6~R9). 원래 트랜잭션이 커밋된 뒤 비동기로,
 * 새 트랜잭션에서 받는다. 그래서 알림이 실패해도 댓글, 공감, HP 반영은 그대로이고(FR-003), 끝나지 않은 발행은 Event
 * Publication Registry에 남아 다시 온다. 같은 이벤트가 여러 번 와도 멱등 키와 참여자 키가 결과를 하나로 만든다.
 *
 * 글이 보이는지는 [PostApi.findVisible] 하나로만 판단한다(규칙 1). 지웠거나 숨긴 글이면 만들지 않는다(005 research R8).
 * 행동한 회원과 받는 사람이 같으면 만들지 않는다(규칙 2, 몬스터 알림은 행동한 회원이 없다). 알림에는 댓글 본문을 담지 않는다.
 */
@Component
class NotificationEventListener(
    private val postApi: PostApi,
    private val monsterApi: MonsterApi,
    private val writer: NotificationWriter,
) {
    /** 원 댓글은 글쓴이에게, 답글은 글쓴이와 원 댓글 주인에게 한 번씩. 받는 사람은 [PostApi.findComment]로 정한다. */
    @ApplicationModuleListener
    fun on(event: CommentCreated) {
        val post = postApi.findVisible(event.postId) ?: return
        val comment = postApi.findComment(event.commentId) ?: return
        writer.writeAll(commentDrafts(post.authorId, comment, event))
    }

    /** 글쓴이의 안 읽은 공감 묶음에 더한다. 댓글 공감(`CommentLiked`)은 알리지 않는다. */
    @ApplicationModuleListener
    fun on(event: PostLiked) {
        val post = postApi.findVisible(event.postId) ?: return
        if (post.authorId == event.memberId) return
        writer.addLike(post.authorId, event.postId, event.memberId)
    }

    /** 기본 몬스터(`defaulted = true`)도 똑같이 알린다(US1-AC3). */
    @ApplicationModuleListener
    fun on(event: MonsterSpawned) {
        val post = postApi.findVisible(event.postId) ?: return
        writer.writeAll(listOf(NotificationDraft.spawned(post.authorId, event.postId, event.monsterId)))
    }

    /**
     * 글쓴이와 HP를 실제로 줄인 회원(research R9). 소급 반영으로 생성과 처치가 한 트랜잭션에서 함께 나간 경우(R8)에만
     * 두 리스너의 실행 순서가 정해지지 않으므로, 그때만 글쓴이의 생성 알림을 먼저 만든다. 같은 카운터 잠금 아래에서 차례로
     * 쓰므로 번호가 "나타났어요" 다음 "처치됐어요"가 된다. 따로 처치된 몬스터는 생성 알림을 다시 만들지 않는다. 보관 기간이
     * 지나 지워진 생성 알림이 새로 생겨 "나타났어요"가 다시 뜨는 일을 막기 위해서다.
     */
    @ApplicationModuleListener
    fun on(event: MonsterDefeated) {
        val post = postApi.findVisible(event.postId) ?: return
        val authorId = post.authorId
        val together =
            (monsterApi.damagerIds(event.monsterId) - authorId).map { memberId ->
                NotificationDraft.defeated(
                    NotificationType.MONSTER_DEFEATED_TOGETHER,
                    memberId,
                    event.postId,
                    event.monsterId,
                )
            }
        val toAuthor =
            listOfNotNull(
                NotificationDraft.spawned(authorId, event.postId, event.monsterId).takeIf { event.retroactive },
                NotificationDraft.defeated(NotificationType.MONSTER_DEFEATED, authorId, event.postId, event.monsterId),
            )
        writer.writeAll(toAuthor + together)
    }

    /**
     * 위험 단계가 올라간 글이나 댓글의 작성자에게 도움 안내를 보낸다(005 US1-AC6). 대상이 숨겨져 있어도 작성자 자신의
     * 것이므로 [PostApi.findVisible]로 거르지 않는다. 문구에는 단계와 글 내용을 싣지 않는다.
     */
    @ApplicationModuleListener
    fun on(event: RiskDetected) {
        val commentId = event.targetId.takeIf { event.targetType == ContentType.COMMENT }
        val dedupKey = "RISK:${event.targetType}:${event.targetId}:${event.level}"
        writer.writeAll(listOf(NotificationDraft.support(event.authorId, event.postId, commentId, dedupKey)))
    }

    private fun commentDrafts(
        postAuthorId: Long,
        comment: CommentSummary,
        event: CommentCreated,
    ): List<NotificationDraft> {
        val actorId = event.memberId

        fun draft(
            type: NotificationType,
            receiverId: Long,
        ) = NotificationDraft.comment(type, receiverId, event.postId, event.commentId, actorId)

        val isReply = comment.parentId != null
        return buildList {
            if (postAuthorId != actorId) {
                add(draft(if (isReply) NotificationType.POST_REPLY else NotificationType.POST_COMMENT, postAuthorId))
            }
            // 원 댓글 주인이 답글 작성자이거나 글쓴이면 이미 위에서 다뤘거나 받을 필요가 없다
            comment.parentAuthorId
                ?.takeIf { it != actorId && it != postAuthorId }
                ?.let { add(draft(NotificationType.COMMENT_REPLY, it)) }
        }
    }
}
