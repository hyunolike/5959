import "server-only";

import { sanitizeNextPath } from "@/shared/lib";

/**
 * bff-routes.md "라우트 가드(proxy.ts)" 표의 판단 로직. `proxy.ts`가 쿠키를 읽어
 * 넘기면, 이 순수 함수가 표를 그대로 따라 리다이렉트 여부를 정한다. Next.js
 * 타입(NextRequest 등)에 의존하지 않아 테스트가 쉽다.
 *
 * | 경로 | 조건 | 동작 |
 * |---|---|---|
 * | `/` | `ogu_rt` 있음 | `/home` |
 * | `/` | `ogu_rt` 없음 | 서비스 소개(랜딩, 회원 데이터 없음) |
 * | 정확한 `/home` | `ogu_rt` 없음 | 익명 서비스 안내(회원 데이터 없음) |
 * | 그 밖의 보호 경로(`/home` 하위, `/write`, `/post`, `/my`, `/settings`, `/notifications`, `/raid`, `/report`, `/cards` 이하) | `ogu_rt` 없음 | `/login?next=<원래 경로>` |
 * | 보호 경로 | `ogu_rt` 있고 `ogu_ob` 없음 | `/onboarding` |
 * | `/onboarding` | `ogu_rt` 없음 | `/login` |
 * | `/onboarding` | `ogu_ob` 있음 | `/home`(검증한 `next`가 있으면 그곳) |
 * | `/login`, `/signup` | `ogu_rt`, `ogu_ob` 모두 있음 | `/home`(검증한 `next`가 있으면 그곳) |
 * | `/withdraw` | `ogu_rt` 없음 | `/login` |
 * | `/withdraw` | `ogu_rt` 있음 | 그대로(온보딩 전에도 탈퇴할 수 있다, 013 US1-AC9) |
 *
 * `next`는 `sanitizeNextPath`를 통과할 때만 따르고, 아니면 `/home`이다.
 */

/**
 * `/post`(글 상세, 003-core-loop)도 API가 온보딩을 마친 회원에게만 열어 두므로
 * 화면이 401/403을 받기 전에 여기서 로그인이나 온보딩으로 보낸다. `/notifications`(알림 목록,
 * 004-notification-mypage FR-014)도 같다.
 */
const PROTECTED_PATH_PREFIXES = [
  "/home",
  "/write",
  "/post",
  "/my",
  "/settings",
  "/notifications",
  "/raid",
  "/report",
  // 몬스터 도감(010 US1-AC8). 목록에 없는 경로는 가드가 그냥 통과시킨다.
  "/cards",
];

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

function redirect(to: string): RouteGuardAction {
  return { type: "redirect", to };
}

function nextFrom(search: string): string {
  return sanitizeNextPath(new URLSearchParams(search).get("next"));
}

/**
 * @param search 요청 URL의 쿼리 문자열(`?`로 시작하거나 빈 문자열).
 */
export function resolveRouteGuardAction(
  pathname: string,
  cookies: RouteGuardCookies,
  search = "",
): RouteGuardAction {
  // 첫 화면은 누구나 보는 서비스 소개다(002 FR-014). 로그인했으면 홈으로 보낸다.
  if (pathname === "/") {
    return cookies.hasRefreshToken ? redirect("/home") : null;
  }

  // 정확한 홈만 익명 안내 화면으로 연다. 피드/상세 API와 나머지 회원 경로는 보호한다.
  if (pathname === "/home" && !cookies.hasRefreshToken) return null;

  if (isProtectedPath(pathname)) {
    if (!cookies.hasRefreshToken) {
      const original = `${pathname}${search}`;
      return redirect(`/login?next=${encodeURIComponent(original)}`);
    }
    return cookies.onboarded ? null : redirect("/onboarding");
  }

  if (pathname === "/onboarding") {
    if (!cookies.hasRefreshToken) {
      return redirect("/login");
    }
    return cookies.onboarded ? redirect(nextFrom(search)) : null;
  }

  // 탈퇴 화면은 로그인만 본다. 보호 경로에 넣으면 온보딩 전 회원이 온보딩으로 되돌려진다.
  if (pathname === "/withdraw") {
    return cookies.hasRefreshToken ? null : redirect("/login");
  }

  if (pathname === "/login" || pathname === "/signup") {
    return cookies.hasRefreshToken && cookies.onboarded
      ? redirect(nextFrom(search))
      : null;
  }

  return null;
}
