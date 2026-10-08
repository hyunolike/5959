/**
 * 모두 읽음에 보낼 `upToSeq`(US2-AC4). 화면이 지금까지 본 번호 가운데 가장 큰 값이다: 목록 첫 항목,
 * 실시간 연결이 마지막으로 받은 이벤트 id, 안 읽은 수 응답의 `latestSeq`. 이 번호 뒤에 생긴 알림은
 * 회원이 아직 보지 못했으므로 안 읽은 채 남는다. 서버는 이 값을 회원의 지금 번호로 낮춘다.
 */
export function resolveUpToSeq({
  firstItemSeq,
  lastEventId,
  latestSeq,
}: {
  firstItemSeq: number | null | undefined;
  lastEventId: number | null | undefined;
  latestSeq: number | null | undefined;
}): number {
  return Math.max(0, firstItemSeq ?? 0, lastEventId ?? 0, latestSeq ?? 0);
}
