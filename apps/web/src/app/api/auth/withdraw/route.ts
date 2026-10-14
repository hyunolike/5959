import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse } from "@/shared/api";
import {
  applyRefreshedSession,
  callApi,
  callWithSessionRefresh,
  clearSessionCookies,
  guardOrigin,
  readSessionCookies,
  resolveClientIp,
} from "@/shared/server";

export const dynamic = "force-dynamic";

const FALLBACK_ERROR: ApiResponse<never> = {
  success: false,
  data: null,
  error: { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." },
};

/**
 * 회원 탈퇴 전용 BFF 라우트(013 research R9). `/api/[...path]` 캐치올은 `members/me/withdrawal`을
 * 404로 막는다. 탈퇴에 성공하면 쿠키를 지워야 하는데 캐치올은 그 일을 하지 않는다.
 *
 * origin-guard → `ogu_at`을 Authorization으로 바꿔 `POST /api/v1/members/me/withdrawal`을 부른다.
 * 화면에 오래 머물러 access 토큰이 만료됐어도 끝낼 수 있게 범용 프록시와 같은 규칙으로 refresh한다.
 *
 * 로그아웃 라우트와 다른 점: **204일 때만** 세 쿠키를 지운다. 로그아웃은 API가 실패해도 브라우저 쪽
 * 상태를 지우지만, 탈퇴는 실패했으면 계정이 그대로 있으므로 로그인한 채 남아 다시 해 볼 수 있어야
 * 한다(US1-AC8). 실패(400, 409, 5xx)는 API의 오류 봉투를 그대로 전달한다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const body = await request.text();
  const clientIp = resolveClientIp(request);
  const outcome = await callWithSessionRefresh(
    readSessionCookies(request),
    clientIp,
    (accessToken) => {
      const headers = new Headers({ "content-type": "application/json" });
      if (accessToken) {
        headers.set("Authorization", `Bearer ${accessToken}`);
      }
      return callApi<never>("/api/v1/members/me/withdrawal", clientIp, {
        method: "POST",
        headers,
        body,
      });
    },
  );
  if (outcome.type === "failed") {
    return outcome.response;
  }

  const { status, body: apiBody } = outcome.result;
  if (status === 204) {
    // 탈퇴했다. refresh로 받은 토큰이 있어도 그 세션은 이미 지워졌으니 쿠키에 싣지 않는다.
    const response = new NextResponse(null, { status: 204 });
    clearSessionCookies(response);
    return response;
  }

  // 2xx인데 204가 아니면 계약 위반이다. 성공으로 보이면 화면이 탈퇴한 줄 안다.
  const isSuccessStatus = status >= 200 && status < 300;
  const response = NextResponse.json(apiBody ?? FALLBACK_ERROR, {
    status: isSuccessStatus ? 502 : status,
  });
  if (outcome.refreshed !== null) {
    applyRefreshedSession(response, outcome.refreshed);
  }
  return response;
}
