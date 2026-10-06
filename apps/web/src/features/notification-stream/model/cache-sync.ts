import type { InfiniteData, QueryClient } from "@tanstack/react-query";

import type {
  NotificationPage,
  StreamNotificationEvent,
  StreamUnreadCountEvent,
  UnreadCount,
} from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

/**
 * `notification` 이벤트를 캐시에 반영한다(research R4, R14).
 *
 * - 목록: 같은 알림 ID를 지우고 첫 쪽 맨 앞에 넣는다. 공감 묶음은 같은 ID가 새 번호로 다시 오므로,
 *   지우지 않으면 한 알림이 두 줄로 보인다. 목록을 아직 불러오지 않았으면 그대로 둔다(열 때 서버에서 받는다).
 * - 안 읽은 수: 서버가 계산한 `unreadCount`로 바꾼다. 묶음 갱신은 수를 늘리지 않으므로 여기서 세지 않는다.
 */
export function applyNotificationEvent(
  queryClient: QueryClient,
  { notification, unreadCount }: StreamNotificationEvent,
): void {
  queryClient.setQueryData<InfiniteData<NotificationPage>>(
    QUERY_KEYS.notifications,
    (list) => {
      if (list === undefined || list.pages.length === 0) {
        return list;
      }
      const pages = list.pages.map((page) => ({
        ...page,
        items: page.items.filter(
          (item) => item.notificationId !== notification.notificationId,
        ),
      }));
      pages[0] = { ...pages[0], items: [notification, ...pages[0].items] };
      return { ...list, pages };
    },
  );
  queryClient.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, (current) => ({
    count: unreadCount,
    latestSeq: Math.max(current?.latestSeq ?? 0, notification.seq),
  }));
}

/** `unread-count` 이벤트는 배지 숫자만 바꾼다. 다른 탭이나 기기에서 읽었을 때 온다. */
export function applyUnreadCountEvent(
  queryClient: QueryClient,
  { unreadCount }: StreamUnreadCountEvent,
): void {
  queryClient.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, (current) =>
    current === undefined ? current : { ...current, count: unreadCount },
  );
}
