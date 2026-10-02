import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import {
  callApi,
  clearSessionCookies,
  guardOrigin,
  readSessionCookies,
  refreshSession,
  resolveClientIp,
} from "@/shared/server";

export const dynamic = "force-dynamic";

/**
 * 로그아웃 전용 BFF 라우트(bff-routes.md). `/api/[...path]` 캐치올은
 * `auth/**`를 전부 404로 막으므로(FR-012), 여기서만 다룬다.
 *
 * origin-guard → `ogu_at`이 있으면 Authorization으로 바꿔 api-client를
 * 호출한다. `ogu_at`이 만료돼 없고 `ogu_rt`만 있으면 먼저 refresh해 받은
 * access 토큰으로 부른다(서버 세션도 무효로 만들기 위해서다). 둘 다 없거나
 * refresh가 실패하면 API를 부르지 않는다 → API 결과와
 * 관계없이(이미 무효인 세션의 401, 네트워크 실패 포함) 세 쿠키를 모두 지우고
 * 204를 돌려준다(US2-AC5). 로그아웃은 브라우저 쪽 상태를 지우는 동작이라
 * API 실패가 브라우저에는 실패로 보이면 안 된다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const clientIp = resolveClientIp(request);
  const cookies = readSessionCookies(request);
  let accessToken = cookies.accessToken;
  if (!accessToken && cookies.refreshToken) {
    const refreshed = await refreshSession(cookies.refreshToken, clientIp);
    if (refreshed.type === "refreshed") {
      accessToken = refreshed.tokens.accessToken;
    }
  }
  if (accessToken) {
    await callApi("/api/v1/auth/logout", clientIp, {
      method: "POST",
      headers: { Authorization: `Bearer ${accessToken}` },
    });
  }

  const response = new NextResponse(null, { status: 204 });
  clearSessionCookies(response);
  return response;
}
