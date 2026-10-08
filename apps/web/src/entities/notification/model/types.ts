import type { components } from "@/shared/api";

export type Notification = components["schemas"]["Notification"];
export type NotificationType = components["schemas"]["NotificationType"];
export type NotificationPage = components["schemas"]["NotificationPage"];
export type UnreadCount = components["schemas"]["UnreadCount"];
/** SSE `event: notification`의 본문. 이벤트 id는 `notification.seq`다. */
export type StreamNotificationEvent =
  components["schemas"]["StreamNotificationEvent"];
/** SSE `event: unread-count`의 본문. id가 없고 다시 보내지 않는다. */
export type StreamUnreadCountEvent =
  components["schemas"]["StreamUnreadCountEvent"];
