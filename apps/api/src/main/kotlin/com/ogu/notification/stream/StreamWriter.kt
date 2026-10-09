package com.ogu.notification.stream

import com.ogu.notification.application.NotificationViewAssembler
import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.presentation.dto.NotificationResponse
import com.ogu.notification.presentation.dto.StreamNotificationEvent
import com.ogu.notification.presentation.dto.StreamUnreadCountEvent
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * 연결에 쓰는 쪽(research R2, R4). 할 일을 표시하고 실행기에 올린다. 연결마다 한 번에 하나만 돌고, 보낼 알림은 언제나
 * DB에서 `seq > 마지막 전송 번호`를 번호 순서로 읽는다.
 *
 * 쓰기에 실패하면 [onBroken]으로 알린다. 두 번째 인자가 true면 응답을 끝내야 하는 경우(DB 오류 등)이고, false면 끊긴
 * 연결이라 컨테이너가 응답을 끝내므로 목록에서 지우기만 하면 된다.
 */
class StreamWriter(
    private val notifications: NotificationRepository,
    private val views: NotificationViewAssembler,
    private val executor: Executor,
    private val onBroken: (StreamConnection, Boolean) -> Unit,
) {
    fun request(
        connection: StreamConnection,
        vararg tasks: StreamTask,
    ) {
        if (connection.closed) return
        tasks.forEach(connection::request)
        if (!connection.tryAcquire()) return
        try {
            executor.execute { work(connection) }
        } catch (e: RejectedExecutionException) {
            // 표시는 남아 있으므로 다음 신호나 안전망이 다시 올린다
            connection.release()
            log.warn("SSE 쓰기 대기열이 가득 찼습니다: {}", e.message)
        }
    }

    /** 쓰기 권한을 쥔 채 표시가 없어질 때까지 처리한다. 놓은 뒤 들어온 표시는 다시 권한을 잡아 처리한다. */
    private fun work(connection: StreamConnection) {
        do {
            // 무슨 일이 있어도(Error 포함) 권한은 놓는다. 쥔 채로 끝나면 그 연결은 다시는 쓰이지 않는다
            val failure =
                try {
                    processPending(connection)
                } finally {
                    connection.release()
                }
            if (failure != null) {
                onBroken(connection, failure)
                return
            }
        } while (!connection.closed && connection.hasPending() && connection.tryAcquire())
    }

    /** 표시된 할 일을 처리한다. 성공하면 null, 실패하면 응답을 끝내야 하는지(true) 아닌지(false). */
    @Suppress("TooGenericExceptionCaught") // 연결 하나의 실패(DB 오류 등)가 실행기 스레드로 번지지 않게 한다
    private fun processPending(connection: StreamConnection): Boolean? =
        try {
            if (!connection.closed) connection.takePending().forEach { perform(connection, it) }
            null
        } catch (e: IOException) {
            log.debug("SSE 연결 {}에 쓰지 못해 지웁니다: {}", connection.id, e.message)
            false
        } catch (e: RuntimeException) {
            if (connection.closed) {
                // 쓰는 사이에 연결이 끝났다(브라우저가 닫음, 수명). 이미 정리됐으니 조용히 넘어간다
                log.debug("끝난 SSE 연결 {}에 쓰지 않습니다: {}", connection.id, e.message)
                false
            } else {
                // DB 오류 등으로 따라잡지 못했다. 닫아서 웹이 마지막으로 받은 번호로 다시 붙게 한다
                log.warn("SSE 연결 {}을 따라잡지 못해 닫습니다: {}", connection.id, e.message)
                true
            }
        }

    private fun perform(
        connection: StreamConnection,
        task: StreamTask,
    ) {
        when (task) {
            StreamTask.DRAIN -> drain(connection)
            StreamTask.UNREAD_COUNT -> {
                val event = StreamUnreadCountEvent(notifications.countUnread(connection.memberId))
                connection.emitter.send(
                    SseEmitter.event().name(UNREAD_COUNT_EVENT).data(event, MediaType.APPLICATION_JSON),
                )
                connection.sentUnreadCount(event.unreadCount)
            }
            // 주석 줄은 중간 장비용이고, 브라우저 EventSource는 주석을 스크립트에 알리지 않는다. 웹이 조용한(죽은) 연결을
            // 알아채도록 이름 있는 ping 이벤트를 같이 보낸다. id가 없어 웹의 마지막 이벤트 id를 바꾸지 않는다
            StreamTask.HEARTBEAT ->
                connection.emitter.send(
                    SseEmitter
                        .event()
                        .comment(HEARTBEAT_COMMENT)
                        .name(PING_EVENT)
                        .data(PING_DATA),
                )
            // 주제 소식은 id가 없어 알림의 마지막 번호를 바꾸지 않는다(006 research R6)
            StreamTask.BROADCAST ->
                connection.takeMessages().forEach { message ->
                    connection.emitter.send(SseEmitter.event().name(message.event).data(message.json))
                }
        }
    }

    /** `seq > 마지막 번호`를 번호 순서로 끝까지 보낸다. 안 읽은 수는 한 묶음마다 한 번 읽는다. */
    private fun drain(connection: StreamConnection) {
        while (!connection.closed) {
            val rows = notifications.findAfterSeq(connection.memberId, connection.lastSentSeq, DRAIN_BATCH)
            if (rows.isEmpty()) return
            val unreadCount = notifications.countUnread(connection.memberId)
            views.assemble(rows).forEach { view ->
                val event = StreamNotificationEvent(NotificationResponse.from(view), unreadCount)
                connection.emitter.send(
                    SseEmitter
                        .event()
                        .id(view.notification.seq.toString())
                        .name(NOTIFICATION_EVENT)
                        .data(event, MediaType.APPLICATION_JSON),
                )
                connection.sent(view.notification.seq)
                connection.sentUnreadCount(unreadCount)
            }
            if (rows.size < DRAIN_BATCH) return
        }
    }

    companion object {
        const val NOTIFICATION_EVENT = "notification"
        const val UNREAD_COUNT_EVENT = "unread-count"

        const val PING_EVENT = "ping"

        /** 계약 `StreamPingEvent`(빈 객체). data가 비면 EventSource가 이벤트를 버린다. */
        private const val PING_DATA = "{}"

        /** `: hb` 주석 줄(계약). Spring은 `:` 뒤에 그대로 붙인다. */
        private const val HEARTBEAT_COMMENT = " hb"
        private const val DRAIN_BATCH = 100
        private val log = LoggerFactory.getLogger(StreamWriter::class.java)
    }
}
