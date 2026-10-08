/**
 * 쪽들을 한 목록으로 펼치되 같은 알림은 한 번만 둔다. 공감 묶음은 쪽 사이에 새 공감이 오면 같은 알림이
 * 더 큰 번호로 다시 온다(research R7). 그때는 번호가 큰 쪽이 최신 내용이므로 그것을 남기고, 자리는 처음
 * 나온 곳을 쓴다.
 */
export function uniqueNotifications<
  T extends { notificationId: number; seq: number },
>(pages: readonly { items: readonly T[] }[]): T[] {
  const newest = new Map<number, T>();
  for (const page of pages) {
    for (const item of page.items) {
      const kept = newest.get(item.notificationId);
      if (kept === undefined || item.seq > kept.seq) {
        newest.set(item.notificationId, item);
      }
    }
  }
  // Map은 처음 넣은 순서를 지키고, 값을 바꿔도 자리는 그대로다.
  return [...newest.values()];
}
