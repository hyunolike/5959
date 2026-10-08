package com.ogu.safety.application

import com.ogu.post.CommentWritten
import com.ogu.post.ContentType
import com.ogu.post.PostModerationApi
import com.ogu.post.PostWritten
import com.ogu.safety.RiskDetected
import com.ogu.safety.RiskLevel
import com.ogu.safety.domain.RiskAssessmentRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * 글과 댓글이 저장되거나 고쳐지는 트랜잭션 안에서 키워드 규칙으로 판정한다(005 research R2). 위기면 그 자리에서 숨기므로
 * 저장과 숨김이 함께 커밋된다. 목록에 있는 위기 표현이 든 글은 한 번도 공개되지 않는다(SC-001).
 *
 * 여기서 도는 것은 메모리 안의 계산과 짧은 쓰기뿐이다. AI 분류는 커밋 뒤에 따로 돈다. 판정은 올리기만 한다. 위기 표현을
 * 지워 고쳐도 숨김은 풀지 않는다(US2-AC5). 작성자에게 가는 알림은 단계가 지금까지보다 높아질 때만 낸다.
 */
@Component
class ScreeningListener(
    private val moderation: PostModerationApi,
    private val termCache: TermCache,
    private val assessments: RiskAssessmentRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    @EventListener
    fun on(event: PostWritten) = screen(ContentType.POST, event.postId)

    @EventListener
    fun on(event: CommentWritten) = screen(ContentType.COMMENT, event.commentId)

    private fun screen(
        type: ContentType,
        id: Long,
    ) {
        val target = moderation.contentOf(type, id) ?: return
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val level = KeywordRule.level(target.content, termCache.terms())
        val highestBefore = assessments.highestLevel(type, id)

        assessments.supersedePending(type, id, now)
        assessments.insertPending(target, level, now)
        moderation.markRisk(type, id, level.name)
        if (level == RiskLevel.CRISIS) moderation.hide(type, id, HIDDEN_BY_RISK)
        if (level > highestBefore) {
            events.publishEvent(RiskDetected(type, id, target.postId, target.authorId, level))
        }
    }

    companion object {
        const val HIDDEN_BY_RISK = "RISK"
    }
}
