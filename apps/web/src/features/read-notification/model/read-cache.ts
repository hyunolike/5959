import type { InfiniteData, QueryClient } from "@tanstack/react-query";

import type {
  Notification,
  NotificationPage,
  UnreadCount,
} from "@/entities/notification";
import { QUERY_KEYS } from "@/shared/config";

import { resolveUpToSeq } from "./up-to-seq";

type ListCache = InfiniteData<NotificationPage>;

/** 목록 캐시의 알림 가운데 [pick]이 고른 것의 읽음 여부를 [read]로 바꾼다. 바꾼 것이 없으면 캐시를 그대로 둔다. */
function setRead(
  queryClient: QueryClient,
  pick: (item: Notification) => boolean,
  read: boolean,
): boolean {
  let changed = false;
  queryClient.setQueryData<ListCache>(QUERY_KEYS.notifications, (list) => {
    if (list === undefined) {
      return list;
    }
    const pages = list.pages.map((page) => ({
      ...page,
      items: page.items.map((item) => {
        if (item.read === read || !pick(item)) {
          return item;
        }
        changed = true;
        return { ...item, read };
      }),
    }));
    return changed ? { ...list, pages } : list;
  });
  return changed;
}

function shiftUnreadCount(queryClient: QueryClient, delta: number) {
  queryClient.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, (current) =>
    current === undefined
      ? current
      : { ...current, count: Math.max(0, current.count + delta) },
  );
}

function findInList(
  queryClient: QueryClient,
  notificationId: number,
): Notification | undefined {
  return queryClient
    .getQueryData<ListCache>(QUERY_KEYS.notifications)
    ?.pages.flatMap((page) => page.items)
    .find((item) => item.notificationId === notificationId);
}

/**
 * 안 읽은 알림 하나를 응답 전에 읽음으로 보인다(US2-AC3): 목록의 그 항목을 읽음으로 바꾸고 배지를 하나 줄인다.
 * 목록에서 이미 읽음이면 아무것도 바꾸지 않는다. 목록에 없으면(목록을 열기 전에 토스트를 눌렀다) 배지만 줄인다.
 *
 * 돌려주는 함수는 실패했을 때 되돌린다. 캐시를 통째로 예전 값으로 덮지 않고 이 알림과 줄인 수만 되돌려서,
 * 응답을 기다리는 동안 실시간으로 들어온 알림과 안 읽은 수를 지우지 않는다.
 */
export function markReadInCache(
  queryClient: QueryClient,
  notificationId: number,
): () => void {
  if (findInList(queryClient, notificationId)?.read) {
    return () => {};
  }
  const isTarget = (item: Notification) =>
    item.notificationId === notificationId;
  setRead(queryClient, isTarget, true);
  shiftUnreadCount(queryClient, -1);
  return () => {
    setRead(queryClient, isTarget, false);
    shiftUnreadCount(queryClient, 1);
  };
}

/**
 * 모두 읽음이 끝난 뒤(US2-AC4) 목록에서 `upToSeq` 이하를 읽음으로 바꾸고, 배지는 서버가 센 수로 맞춘다.
 * 누르는 사이에 온 알림은 번호가 더 커서 안 읽은 채 남는다.
 */
export function markAllReadInCache(
  queryClient: QueryClient,
  upToSeq: number,
  unreadCount: number,
): void {
  setRead(queryClient, (item) => item.seq <= upToSeq, true);
  queryClient.setQueryData<UnreadCount>(QUERY_KEYS.unreadCount, (current) =>
    current === undefined ? current : { ...current, count: unreadCount },
  );
}

/**
 * 화면이 지금 아는 가장 큰 번호([resolveUpToSeq]). 목록 첫 항목과 `latestSeq`는 캐시에서 읽고, 실시간
 * 연결의 마지막 이벤트 id는 부르는 쪽이 넘긴다(연결 상태는 다른 feature의 스토어에 있다).
 */
export function knownUpToSeq(
  queryClient: QueryClient,
  lastEventId: number | null,
): number {
  const list = queryClient.getQueryData<ListCache>(QUERY_KEYS.notifications);
  const unread = queryClient.getQueryData<UnreadCount>(QUERY_KEYS.unreadCount);
  return resolveUpToSeq({
    firstItemSeq: list?.pages[0]?.items[0]?.seq,
    lastEventId,
    latestSeq: unread?.latestSeq,
  });
}
