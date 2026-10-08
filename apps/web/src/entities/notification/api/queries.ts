import {
  queryOptions,
  useInfiniteQuery,
  useQuery,
} from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { NotificationPage, UnreadCount } from "../model/types";
import { newestUnreadCount } from "../model/unread";

/**
 * 같은 출처 BFF 프록시를 거쳐 안 읽은 알림 수와 마지막 전달 번호를 받는다. `latestSeq`는
 * 실시간 연결을 처음 열 때 `lastEventId`로 넘긴다(목록과 스트림 사이 틈을 막는다, research R4).
 */
export function fetchUnreadCount(
  fetchImpl: typeof fetch = fetch,
): Promise<UnreadCount> {
  return requestApi<UnreadCount>(
    "/api/notifications/unread-count",
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 안 읽은 수 조회 옵션. 조회 결과가 캐시보다 옛것이면 버린다([newestUnreadCount]). 이 키로 조회하는
 * 곳은 모두 이 옵션을 써야 같은 규칙이 적용된다.
 */
export function unreadCountQueryOptions() {
  return queryOptions({
    queryKey: QUERY_KEYS.unreadCount,
    queryFn: () => fetchUnreadCount(),
    structuralSharing: (cached, incoming) =>
      newestUnreadCount(
        cached as UnreadCount | undefined,
        incoming as UnreadCount,
      ),
  });
}

/**
 * 안 읽은 알림 수(배지). 실시간 이벤트가 이 캐시를 고치고, 다시 연결하면 새로 받는다.
 * `enabled`가 false면 부르지 않는다. 로그아웃해 연결을 닫은 뒤에는 끝난 세션으로 조회하지 않는다.
 */
export function useUnreadCountQuery({ enabled = true } = {}) {
  return useQuery({ ...unreadCountQueryOptions(), enabled });
}

/**
 * 알림 목록 한 쪽. 커서는 서버가 준 불투명한 값을 그대로 돌려준다. 쪽 크기는 서버 기본값(20)을 쓴다.
 */
export function fetchNotificationsPage(
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<NotificationPage> {
  const query = cursor ? `?${new URLSearchParams({ cursor })}` : "";
  return requestApi<NotificationPage>(
    `/api/notifications${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 알림 목록(US2-AC1, AC2). 번호(`seq`) 내림차순 키셋으로 20개씩 이어 붙인다.
 *
 * 실시간 이벤트가 같은 캐시(`QUERY_KEYS.notifications`)의 첫 쪽 앞에 새 알림을 넣는다. 그래서 목록을
 * 열 때마다 다시 받는다. 화면을 떠나 있던 동안 캐시가 남아 있어도, 끊긴 사이에 빠진 것이 없게 서버 값으로 맞춘다.
 */
export function useNotificationsQuery() {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.notifications,
    queryFn: ({ pageParam }) => fetchNotificationsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
  });
}
