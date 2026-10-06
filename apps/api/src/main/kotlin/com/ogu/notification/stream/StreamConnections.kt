package com.ogu.notification.stream

import java.util.concurrent.ConcurrentHashMap

/**
 * 이 인스턴스의 회원별 연결 목록. 회원 한 명의 연결은 [maxPerMember]개까지 두고, 넘으면 가장 오래된 것부터 밀어낸다.
 * 목록이 비면 회원 항목도 지워 끝난 연결이 남지 않는다.
 */
class StreamConnections(
    private val maxPerMember: Int,
) {
    private val byMember = ConcurrentHashMap<Long, List<StreamConnection>>()

    /** 등록하고 상한을 넘어 밀려난 연결(가장 오래된 것부터)을 돌려준다. 닫기는 부르는 쪽이 맵 잠금 밖에서 한다. */
    fun register(connection: StreamConnection): List<StreamConnection> {
        var evicted = emptyList<StreamConnection>()
        byMember.compute(connection.memberId) { _, current ->
            val list = current.orEmpty()
            val overflow = list.size + 1 - maxPerMember
            if (overflow > 0) evicted = list.take(overflow)
            list.drop(overflow.coerceAtLeast(0)) + connection
        }
        return evicted
    }

    fun remove(connection: StreamConnection) {
        byMember.computeIfPresent(connection.memberId) { _, list -> (list - connection).ifEmpty { null } }
    }

    /** 응답은 건드리지 않고(이미 끝났거나 컨테이너가 끝낸다) 닫힌 것으로 표시하고 목록에서 지운다. */
    fun discard(connection: StreamConnection) {
        connection.markClosed()
        remove(connection)
    }

    fun of(memberId: Long): List<StreamConnection> = byMember[memberId].orEmpty()

    fun all(): List<StreamConnection> = byMember.values.flatten()

    fun memberIds(): Set<Long> = byMember.keys.toSet()
}
