package com.ogu.notification.presentation.dto

import com.ogu.notification.application.ReadAllResult

/** 계약의 `ReadAllRequest`. 필드가 빠지면 null로 받아 서비스 앞에서 400으로 거절한다. */
data class ReadAllRequest(
    val upToSeq: Long? = null,
)

/** 계약의 `ReadAllResult`. */
data class ReadAllResponse(
    val updated: Int,
    val unreadCount: Long,
) {
    companion object {
        fun from(result: ReadAllResult): ReadAllResponse = ReadAllResponse(result.updated, result.unreadCount)
    }
}
