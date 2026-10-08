package com.ogu.notification.stream

/** [RedisSignalSubscriber]가 채널에서 받은 신호를 넘기는 곳. 이 인스턴스의 SSE 연결 허브가 구현한다. */
fun interface NotificationSignalHandler {
    fun onSignal(signal: NotificationSignal)
}
