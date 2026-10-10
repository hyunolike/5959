package com.ogu.notification.application

import com.ogu.notification.domain.LikeParticipantRepository
import com.ogu.notification.domain.NewNotification
import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationSequenceRepository
import com.ogu.notification.stream.NotificationSignal
import com.ogu.notification.stream.RedisSignalPublisher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 알림을 쓴다(data-model.md "알림 생성 규칙" 3~5, research R4, R6, R7). 부르는 쪽의 트랜잭션 안에서만 쓴다. 알림 리스너
 * ([NotificationEventListener])의 트랜잭션이 그것이고, 그 안에서 번호, 알림 행, 참여자가 함께 커밋되거나 함께 사라진다.
 *
 * - 번호는 받는 사람의 카운터 행에서 받는다. 그 행 잠금이 커밋까지 남아 같은 회원의 쓰기를 줄 세운다. 여러 회원에게 쓰면
 *   회원 ID 오름차순으로 받아 교착을 막는다.
 * - 쓴 회원마다 커밋 뒤 `{memberId}:n:{seq}` 신호를 예약한다([RedisSignalPublisher]). 되돌리면 나가지 않는다.
 * - 멱등 키 확인과 번호 받기 사이에 같은 알림이 먼저 커밋되면(소급 처치에서 생성 리스너와 처치 리스너가 겹친 경우,
 *   research R8) 삽입은 건너뛰지만 받은 번호는 빈 채로 남는다. 재전송은 `seq >` 비교라 빈칸은 해가 없다(research R4).
 */
@Component
class NotificationWriter(
    private val sequences: NotificationSequenceRepository,
    private val notifications: NotificationRepository,
    private val participants: LikeParticipantRepository,
    private val signals: RedisSignalPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 멱등 키가 있는 알림(댓글, 답글, 몬스터)을 쓴다. 받는 사람 ID 오름차순으로 쓰고, 같은 받는 사람이면 넘긴 순서를
     * 지킨다(생성 알림이 처치 알림보다 앞 번호를 받는다, research R8). 같은 키의 알림이 이미 있으면 번호도 받지 않고
     * 건너뛰고, 겹쳐 들어온 처리에 밀려 삽입되지 않으면 신호를 보내지 않는다.
     */
    fun writeAll(drafts: List<NotificationDraft>) {
        requireTransaction()
        drafts.sortedBy { it.receiverId }.forEach(::write)
    }

    /**
     * 글쓴이 [receiverId]의 안 읽은 공감 묶음에 [likerId]의 공감을 더한다(research R7). 번호를 받고, 묶음을 고치거나 만들고,
     * 그 묶음으로 참여자를 넣는다. 참여자가 이미 있으면(이미 알린 공감) 부르는 쪽 트랜잭션 전체를 되돌려 번호와 묶음 갱신을
     * 모두 취소하고 false다.
     */
    fun addLike(
        receiverId: Long,
        postId: Long,
        likerId: Long,
    ): Boolean {
        requireTransaction()
        val seq = sequences.next(receiverId)
        val now = now()
        val groupId = notifications.upsertLikeGroup(receiverId, postId, likerId, seq, now)
        if (!participants.insertIfAbsent(postId, likerId, groupId, now)) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()
            log.debug("이미 알린 공감이라 묶음을 바꾸지 않습니다: postId={}, likerId={}", postId, likerId)
            return false
        }
        signals.publish(NotificationSignal.New(receiverId, seq))
        return true
    }

    private fun write(draft: NotificationDraft) {
        if (notifications.existsByDedupKey(draft.receiverId, draft.dedupKey)) return
        val seq = sequences.next(draft.receiverId)
        val notification =
            NewNotification(
                receiverId = draft.receiverId,
                type = draft.type,
                postId = draft.postId,
                commentId = draft.commentId,
                monsterId = draft.monsterId,
                actorId = draft.actorId,
                dedupKey = draft.dedupKey,
                seq = seq,
                createdAt = now(),
                raidBossId = draft.raidBossId,
                reportWeekStart = draft.reportWeekStart,
            )
        notifications.insertIfAbsent(notification) ?: return
        signals.publish(NotificationSignal.New(draft.receiverId, seq))
    }

    private fun requireTransaction() {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "알림은 트랜잭션 안에서만 쓴다" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
