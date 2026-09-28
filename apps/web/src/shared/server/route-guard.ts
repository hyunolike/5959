import "server-only";

/**
 * bff-routes.md "라우트 가드(proxy.ts)" 표의 판단 로직. `proxy.ts`가 쿠키를 읽어
 * 넘기면, 이 순수 함수가 표를 그대로 따라 리다이렉트 여부를 정한다. Next.js
 * 타입(NextRequest 등)에 의존하지 않아 테스트가 쉽다.
 *
 * 이 배치(T039)는 표 중 US1 범위 두 행만 구현한다:
 * - 보호 경로(`/home`, `/write`, `/my`, `/settings` 이하) + `ogu_rt` 있음 +
 *   `ogu_ob` 없음 → `/onboarding`
 * - `/onboarding` + `ogu_ob` 있음 → `/home`
 *
 * 나머지 행(`/` → `/home`, 보호 경로에 `ogu_rt`가 없을 때 `/login?next=`,
 * `/onboarding`에 `ogu_rt`가 없을 때 `/login`, `/login`·`/signup` → `/home`,
 * `next` 검증)은 T063(US4)에서 이 파일에 이어서 추가한다.
 */

const PROTECTED_PATH_PREFIXES = ["/home", "/write", "/my", "/settings"];

export interface RouteGuardCookies {
  hasRefreshToken: boolean;
  onboarded: boolean;
}

export type RouteGuardAction = { type: "redirect"; to: string } | null;

function isProtectedPath(pathname: string): boolean {
  return PROTECTED_PATH_PREFIXES.some(
    (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
  );
}

export function resolveRouteGuardAction(
  pathname: string,
  cookies: RouteGuardCookies,
): RouteGuardAction {
  if (pathname === "/onboarding") {
    if (cookies.onboarded) {
      return { type: "redirect", to: "/home" };
    }
    return null;
  }

  if (
    isProtectedPath(pathname) &&
    cookies.hasRefreshToken &&
    !cookies.onboarded
  ) {
    return { type: "redirect", to: "/onboarding" };
  }

  return null;
}
