export {
  fetchUnreadCount,
  unreadCountQueryOptions,
  useUnreadCountQuery,
} from "./api/queries";
export { BADGE_MAX, badgeLabel } from "./model/badge";
export { notificationMessage } from "./model/message";
export { newestUnreadCount } from "./model/unread";
export type {
  Notification,
  NotificationPage,
  NotificationType,
  StreamNotificationEvent,
  StreamUnreadCountEvent,
  UnreadCount,
} from "./model/types";
