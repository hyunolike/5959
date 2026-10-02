/**
 * 429 LOGIN_THROTTLED의 `retryAfterSeconds`를 분 단위 안내로 바꾼다
 * (US2-AC3, US2-AC4). 올림하고, 남은 시간이 있으면 최소 1분으로 보여준다.
 */
export function retryWaitMinutes(retryAfterSeconds: number): number {
  return Math.max(1, Math.ceil(retryAfterSeconds / 60));
}
