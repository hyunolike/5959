import type { UnreadCount } from "./types";

/**
 * 캐시에 둘 안 읽은 수를 고른다. 받은 값의 마지막 번호가 캐시보다 작으면 늦게 도착한 옛 응답이다.
 * 조회가 진행 중일 때 실시간 이벤트가 먼저 캐시를 고친 경우이므로 캐시를 그대로 둔다.
 */
export function newestUnreadCount(
  cached: UnreadCount | undefined,
  incoming: UnreadCount,
): UnreadCount {
  return cached !== undefined && incoming.latestSeq < cached.latestSeq
    ? cached
    : incoming;
}
