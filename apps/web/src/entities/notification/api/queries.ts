import { useQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { UnreadCount } from "../model/types";

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
 * 안 읽은 알림 수(배지). 실시간 이벤트가 이 캐시를 고치고, 다시 연결하면 새로 받는다.
 * `enabled`가 false면 부르지 않는다. 로그아웃해 연결을 닫은 뒤에는 끝난 세션으로 조회하지 않는다.
 */
export function useUnreadCountQuery({ enabled = true } = {}) {
  return useQuery({
    queryKey: QUERY_KEYS.unreadCount,
    queryFn: () => fetchUnreadCount(),
    enabled,
  });
}
