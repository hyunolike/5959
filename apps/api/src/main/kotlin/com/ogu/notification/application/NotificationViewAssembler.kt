package com.ogu.notification.application

import com.ogu.member.MemberApi
import com.ogu.notification.domain.Notification
import com.ogu.post.PostApi
import org.springframework.stereotype.Component

/** 알림에 화면이 필요한 것(글 앞부분, 행동한 회원의 지금 닉네임)을 붙인 것. 글이 지워졌으면 [post]가 null이다. */
data class NotificationView(
    val notification: Notification,
    val post: NotificationPostView?,
    val actor: NotificationActorView?,
)

data class NotificationPostView(
    val postId: Long,
    val contentPreview: String,
)

data class NotificationActorView(
    val id: Long,
    val nickname: String,
)

/**
 * 알림 여러 개에 글 미리보기와 행동한 회원을 붙인다. 글은 [PostApi.previews], 회원은 [MemberApi.getMembers]로 한 번씩만
 * 읽는다. 행동한 회원이 없거나(몬스터 알림) 닉네임이 없으면 [NotificationView.actor]가 null이다.
 */
@Component
class NotificationViewAssembler(
    private val postApi: PostApi,
    private val memberApi: MemberApi,
) {
    fun assemble(notifications: List<Notification>): List<NotificationView> {
        if (notifications.isEmpty()) return emptyList()
        val previews = postApi.previews(notifications.map { it.postId })
        val members = memberApi.getMembers(notifications.mapNotNull { it.latestActorId })
        return notifications.map { notification ->
            val post = previews[notification.postId]?.takeUnless { it.deleted }
            val actor =
                notification.latestActorId?.let { id ->
                    members[id]?.nickname?.let { nickname -> NotificationActorView(id, nickname) }
                }
            NotificationView(
                notification = notification,
                post = post?.let { NotificationPostView(it.postId, it.contentPreview) },
                actor = actor,
            )
        }
    }
}
