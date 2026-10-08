package com.ogu.notification.presentation.dto

import com.ogu.notification.application.UnreadCount

/** 계약의 `UnreadCount`. 99를 넘는 수의 "99+" 표시는 웹이 한다. */
data class UnreadCountResponse(
    val count: Long,
    val latestSeq: Long,
) {
    companion object {
        fun from(unread: UnreadCount): UnreadCountResponse = UnreadCountResponse(unread.count, unread.latestSeq)
    }
}
