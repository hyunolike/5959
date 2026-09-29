import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { readSessionCookies, resolveRouteGuardAction } from "@/shared/server";

/**
 * 페이지 라우트 가드. 판단 로직은 순수 함수 `shared/server/route-guard.ts`에
 * 있다(bff-routes.md "라우트 가드(proxy.ts)" 표). 이 파일은 쿠키를 읽어
 * 넘기고, 결과를 리다이렉트로 바꾸는 얇은 어댑터다.
 *
 * `/api/**`, 정적 자산, 파비콘은 matcher에서 제외한다 — `/api/[...path]`는
 * 자기만의 origin-guard가 있고, 정적 자산에 가드를 걸면 CSS/JS가 막힐 수 있다.
 */
export function proxy(request: NextRequest): NextResponse {
  const { refreshToken, onboarded } = readSessionCookies(request);

  const action = resolveRouteGuardAction(
    request.nextUrl.pathname,
    { hasRefreshToken: refreshToken !== undefined, onboarded },
    request.nextUrl.search,
  );

  if (action !== null) {
    return NextResponse.redirect(new URL(action.to, request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
