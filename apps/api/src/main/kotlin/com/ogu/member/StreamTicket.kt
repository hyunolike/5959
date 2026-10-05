package com.ogu.member

import java.time.Instant

/** SSE 연결 표(004 research R3). [ticket]은 원문이고 서버는 해시만 저장한다. 한 번만, [expiresAt] 전까지 쓸 수 있다. */
data class StreamTicket(
    val ticket: String,
    val expiresAt: Instant,
)
