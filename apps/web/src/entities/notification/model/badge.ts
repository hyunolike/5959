/** 배지가 숫자로 보여 주는 가장 큰 수. 넘으면 "99+"다. */
export const BADGE_MAX = 99;

/**
 * 안 읽은 알림 배지에 쓸 글자. 0이면 `null`(배지를 숨긴다), 1부터 99까지는 숫자,
 * 100 이상은 "99+"다(FR-009).
 */
export function badgeLabel(count: number): string | null {
  if (count <= 0) {
    return null;
  }
  return count > BADGE_MAX ? `${BADGE_MAX}+` : String(count);
}
