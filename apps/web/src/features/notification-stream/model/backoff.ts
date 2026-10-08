export const BACKOFF_BASE_MS = 1000;
export const BACKOFF_MAX_MS = 30_000;
/** 대기 시간을 위아래로 흔드는 비율(±20%). */
export const BACKOFF_JITTER = 0.2;

/**
 * 다시 연결하기 전에 기다릴 시간(research R14). `attempt`는 연달아 실패한 횟수로, 0부터 1초,
 * 2초, 4초로 두 배씩 늘고 30초에서 멈춘다. 여러 탭이 한꺼번에 다시 붙지 않게 ±20% 흔든다.
 * 연결이 20초 동안 열려 있으면 호출한 쪽이 `attempt`를 0으로 되돌려 다시 1초부터 시작한다.
 */
export function backoffDelayMs(
  attempt: number,
  random: () => number = Math.random,
): number {
  const base = Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** attempt);
  const jitter = (random() * 2 - 1) * BACKOFF_JITTER;
  return Math.round(base * (1 + jitter));
}
