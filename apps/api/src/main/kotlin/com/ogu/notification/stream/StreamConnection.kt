package com.ogu.notification.stream

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** 연결에 해 둔 할 일 표시. 여러 번 표시해도 한 번 처리된다. */
enum class StreamTask {
    /** DB에서 `seq > 마지막 전송 번호`를 읽어 보낸다. */
    DRAIN,

    /** 안 읽은 수(`unread-count` 이벤트)를 보낸다. */
    UNREAD_COUNT,

    /** `: hb` 주석 줄을 보낸다. */
    HEARTBEAT,
}

/**
 * SSE 연결 하나(research R2, R5). [lastSentSeq]는 이 연결에 마지막으로 보낸 알림 번호다. 따라잡기는 언제나 DB에서
 * `seq > lastSentSeq`를 읽어 보내고 번호를 올리므로, 같은 번호를 두 번 보내지 않는다.
 *
 * 쓰기는 연결마다 한 번에 하나만 돈다([tryAcquire], [release]). 할 일은 [request]로 표시만 해 두고, 쓰기 권한을 쥔 쪽이
 * 표시가 없어질 때까지 처리한다. 신호가 여러 번 와도 따라잡기 한 번으로 합쳐진다.
 */
class StreamConnection(
    val id: Long,
    val memberId: Long,
    val emitter: SseEmitter,
    startSeq: Long,
) {
    @Volatile
    var lastSentSeq: Long = startSeq
        private set

    private val closedFlag = AtomicBoolean()
    private val running = AtomicBoolean()
    private val pending: MutableSet<StreamTask> = ConcurrentHashMap.newKeySet()

    val closed: Boolean get() = closedFlag.get()

    fun request(task: StreamTask) {
        pending += task
    }

    /** 표시돼 있던 할 일을 지우며 돌려준다. [StreamTask] 순서(따라잡기, 안 읽은 수, 하트비트)대로다. */
    fun takePending(): Set<StreamTask> =
        EnumSet.noneOf(StreamTask::class.java).apply {
            StreamTask.entries.filter { pending.remove(it) }.forEach(::add)
        }

    fun hasPending(): Boolean = pending.isNotEmpty()

    /** 쓰기 권한. 이미 다른 스레드가 쥐고 있으면 false이고, 그 스레드가 남은 표시까지 처리한다. */
    fun tryAcquire(): Boolean = running.compareAndSet(false, true)

    fun release() = running.set(false)

    /** 쓰기 권한을 쥔 스레드만 부른다. 번호는 줄지 않는다. */
    fun sent(seq: Long) {
        if (seq > lastSentSeq) lastSentSeq = seq
    }

    /** 처음 한 번만 true. */
    fun markClosed(): Boolean = closedFlag.compareAndSet(false, true)
}
