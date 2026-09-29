import { sanitizeNextPath, withNextPath } from "@/shared/lib";

/**
 * 로그인 뒤 갈 곳(US4-AC5). 온보딩을 마쳤으면 검증한 `next`(기본 `/home`),
 * 아니면 `next`를 들고 `/onboarding`으로 간다.
 */
export function loginDestination(
  onboarded: boolean,
  next: string | null | undefined,
): string {
  return onboarded ? sanitizeNextPath(next) : withNextPath("/onboarding", next);
}
