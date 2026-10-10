package com.ogu.notification.application

import com.ogu.report.WeeklyReportPublished
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * 주간 리포트가 발행되면 그 회원에게 알린다(008 research R4). [NotificationEventListener]와 같은 방식으로 커밋 뒤 새
 * 트랜잭션에서 받는다. 이벤트가 다시 와도 멱등 키(`WEEKLY_REPORT:<주>`)가 알림을 하나로 만든다. 글이 없는 알림이다.
 */
@Component
class WeeklyReportNotificationListener(
    private val writer: NotificationWriter,
) {
    @ApplicationModuleListener
    fun on(event: WeeklyReportPublished) {
        writer.writeAll(listOf(NotificationDraft.weeklyReport(event.memberId, event.weekStart)))
    }
}
