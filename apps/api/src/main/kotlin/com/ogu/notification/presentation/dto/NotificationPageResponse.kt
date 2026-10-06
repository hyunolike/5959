package com.ogu.notification.presentation.dto

import com.ogu.notification.application.NotificationPage

/** 계약의 `NotificationPage`. 항목은 번호 내림차순(최신순)이고, 다음 쪽이 없으면 [nextCursor]는 null이다. */
data class NotificationPageResponse(
    val items: List<NotificationResponse>,
    val nextCursor: String?,
) {
    companion object {
        fun from(page: NotificationPage): NotificationPageResponse =
            NotificationPageResponse(items = page.items.map(NotificationResponse::from), nextCursor = page.nextCursor)
    }
}
