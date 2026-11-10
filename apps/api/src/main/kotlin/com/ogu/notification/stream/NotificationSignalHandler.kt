package com.ogu.notification.stream

/** [LocalSignalPublisher]가 신호를 넘기는 곳. 이 서버의 SSE 연결 허브가 구현한다. */
fun interface NotificationSignalHandler {
    fun onSignal(signal: NotificationSignal)
}
