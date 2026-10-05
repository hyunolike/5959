package com.ogu.notification.stream

import com.ogu.notification.application.NotificationProperties
import com.ogu.notification.application.NotificationViewAssembler
import com.ogu.notification.domain.NotificationRepository
import com.ogu.notification.domain.NotificationSequenceRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * 이 인스턴스의 SSE 연결(research R2, R4, R5).
 *
 * - 회원별 연결 목록을 들고, 회원 한 명의 연결은 [NotificationProperties.maxConnectionsPerMember]개까지 둔다. 넘으면 가장
 *   오래된 연결을 닫는다.
 * - 보내는 것은 언제나 DB에서 번호로 읽은 것이다([StreamWriter]). Redis 신호는 "이 회원에게 새 것이 있다"는 힌트일 뿐이라,
 *   신호를 잃거나 두 번 받아도 빠지거나 겹치지 않는다. 신호의 번호까지 이미 보낸 연결은 쿼리 없이 건너뛴다.
 * - 새 연결은 허브에 먼저 등록한 뒤 첫 따라잡기(재전송)를 한다. 재전송 쿼리와 실시간 구독 사이에 커밋된 알림의 신호가
 *   등록된 연결에 닿아 다시 따라잡게 하기 위해서다. 순서를 바꾸면 그 사이 알림은 안전망 주기까지 빠진다.
 * - 쓰기는 전용 실행기에서 한다(요청 스레드와 구독 스레드를 붙잡지 않는다). 연결이 끝나거나(완료, 수명, 오류 콜백) 쓰기에
 *   실패하면 목록에서 지운다.
 */
@Component
class SseHub(
    private val notifications: NotificationRepository,
    views: NotificationViewAssembler,
    private val sequences: NotificationSequenceRepository,
    private val properties: NotificationProperties,
    @Qualifier(StreamConfig.STREAM_EXECUTOR) executor: Executor,
) : NotificationSignalHandler,
    SmartLifecycle {
    private val connections = StreamConnections(properties.maxConnectionsPerMember)

    // 쓰기 실패. 끊긴 연결이면 컨테이너가 응답을 끝내므로 지우기만 하고, 그 밖에는 닫는다
    private val writer =
        StreamWriter(notifications, views, executor) { connection, complete ->
            if (complete) {
                close(connection)
            } else {
                connection.markClosed()
                connections.remove(connection)
            }
        }
    private val ids = AtomicLong()

    @Volatile
    private var running = false

    /**
     * 연결을 연다. [lastEventId]가 있으면 그 뒤 번호를, 없으면 지금 이 회원의 마지막 번호 뒤부터 보낸다. 보관 기간이 지난 알림은
     * 다시 보내지 않는다.
     */
    fun open(
        memberId: Long,
        lastEventId: Long?,
    ): SseEmitter {
        val startSeq = lastEventId ?: sequences.current(memberId)
        val emitter = SseEmitter(properties.connectionLifetime.toMillis())
        val connection = StreamConnection(ids.incrementAndGet(), memberId, emitter, startSeq)
        emitter.onCompletion { connections.remove(connection) }
        // 연결 수명(15분)이 지나면 서버가 닫는다. 웹은 새 표와 마지막 번호로 다시 붙는다
        emitter.onTimeout { close(connection) }
        emitter.onError { connections.remove(connection) }
        connections.register(connection).forEach(::close)
        // 등록한 뒤에 재전송한다. 이어서 하트비트를 한 번 보내 응답 헤더를 바로 내보낸다(보낼 알림이 없어도 연결이 열린다)
        writer.request(connection, StreamTask.DRAIN, StreamTask.HEARTBEAT)
        return emitter
    }

    /** 신호를 받았다. 새 알림이면 그 번호까지 아직 보내지 않은 연결만 따라잡는다. 읽음이면 안 읽은 수를 보낸다. */
    override fun onSignal(signal: NotificationSignal) {
        val targets = connections.of(signal.memberId)
        when (signal) {
            is NotificationSignal.New ->
                targets.filter { signal.seq > it.lastSentSeq }.forEach { writer.request(it, StreamTask.DRAIN) }
            is NotificationSignal.Read -> targets.forEach { writer.request(it, StreamTask.UNREAD_COUNT) }
        }
    }

    /** 모든 연결을 한 번씩 따라잡는다. Redis 구독이 돌아왔을 때 끊긴 동안 놓친 신호를 메운다. */
    fun drainAll() {
        connections.all().forEach { writer.request(it, StreamTask.DRAIN) }
    }

    /** 안전망(research R5): 회원마다 가장 큰 번호를 한 번에 읽어 뒤처진 연결만 따라잡는다. */
    fun drainLagging() {
        val memberIds = connections.memberIds()
        if (memberIds.isEmpty()) return
        val latest = notifications.maxSeqByReceivers(memberIds)
        connections
            .all()
            .filter { (latest[it.memberId] ?: 0L) > it.lastSentSeq }
            .forEach { writer.request(it, StreamTask.DRAIN) }
    }

    /** 모든 연결에 하트비트 주석 줄을 보낸다. 쓰기에 실패한 연결은 지운다. */
    fun heartbeatAll() {
        connections.all().forEach { writer.request(it, StreamTask.HEARTBEAT) }
    }

    fun connectionCount(memberId: Long): Int = connections.of(memberId).size

    override fun start() {
        running = true
    }

    /** 종료할 때 연결을 먼저 닫는다. 그래야 웹 서버의 우아한 종료가 열린 스트림을 기다리지 않는다. */
    override fun stop() {
        running = false
        connections.all().forEach(::close)
    }

    override fun isRunning(): Boolean = running

    @Suppress("TooGenericExceptionCaught") // 이미 끝난 연결을 닫는 실패는 무시한다
    private fun close(connection: StreamConnection) {
        connections.remove(connection)
        if (!connection.markClosed()) return
        try {
            connection.emitter.complete()
        } catch (e: RuntimeException) {
            log.debug("이미 끝난 연결을 닫지 못했습니다: {}", e.message)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(SseHub::class.java)
    }
}
