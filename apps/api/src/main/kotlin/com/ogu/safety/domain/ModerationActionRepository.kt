package com.ogu.safety.domain

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** `moderation_action` 저장소(005 data-model.md). 운영자가 한 일을 넣기만 한다. 고치거나 지우지 않는다(FR-012). */
@Repository
class ModerationActionRepository(
    private val jdbcClient: JdbcClient,
) {
    fun record(action: NewModerationAction): Long =
        jdbcClient
            .sql(
                """
                insert into moderation_action (operator_id, action, target_type, target_id, note, created_at)
                values (:operatorId, :action, :targetType, :targetId, :note, :now)
                returning id
                """.trimIndent(),
            ).param("operatorId", action.operatorId)
            .param("action", action.action.name)
            .param("targetType", action.targetType)
            .param("targetId", action.targetId)
            .param("note", action.note)
            .param("now", Timestamp.from(action.createdAt))
            .query(Long::class.java)
            .single()
}

enum class ModerationActionType {
    HIDE,
    UNHIDE,
    RESOLVE_REPORT,
    REJECT_REPORT,
    KEEP_HIDDEN,
    ADD_TERM,
    REMOVE_TERM,
}

/** [targetType]은 `POST`, `COMMENT`, `TERM` 가운데 하나다. [note]에는 본문을 옮겨 적지 않는다. */
data class NewModerationAction(
    val operatorId: Long,
    val action: ModerationActionType,
    val targetType: String,
    val targetId: Long,
    val note: String?,
    val createdAt: Instant,
)
