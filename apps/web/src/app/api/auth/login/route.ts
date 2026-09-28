import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse, components } from "@/shared/api";
import {
  callApi,
  clearOnboardedCookie,
  guardOrigin,
  resolveClientIp,
  setOnboardedCookie,
  setSessionCookies,
} from "@/shared/server";

export const dynamic = "force-dynamic";

type AuthResult = components["schemas"]["AuthResult"];

const FALLBACK_ERROR: ApiResponse<never> = {
  success: false,
  data: null,
  error: { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." },
};

/**
 * 이메일 로그인 전용 BFF 라우트(bff-routes.md). `/api/[...path]` 캐치올은
 * `auth/**`를 전부 404로 막으므로(FR-012), 여기서만 다룬다.
 *
 * origin-guard → api-client 호출(429의 `Retry-After` 헤더도 `forwardResponseHeaders`로
 * 받는다) → 성공하면 `ogu_at`, `ogu_rt`를 심고, 온보딩 여부에 따라 `ogu_ob`를
 * 설정하거나 지운다 → 본문에는 `member`만 남긴다. 실패(400, 401, 429)는 API의
 * 오류 봉투와 상태, 429의 `Retry-After` 헤더를 그대로 전달한다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const body = await request.text();
  const {
    status,
    body: apiBody,
    headers,
  } = await callApi<AuthResult>(
    "/api/v1/auth/login",
    resolveClientIp(request),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
      forwardResponseHeaders: ["retry-after"],
    },
  );

  if (apiBody === null) {
    // apps/api가 2xx인데 본문이 비어 있는 건 계약 위반이다 — 그 2xx 상태를
    // 그대로 돌려주면 브라우저가 로그인에 성공한 줄 알게 된다.
    const isSuccessStatus = status >= 200 && status < 300;
    return NextResponse.json(FALLBACK_ERROR, {
      status: isSuccessStatus ? 502 : status,
    });
  }
  if (!apiBody.success) {
    const errorResponse = NextResponse.json(apiBody, { status });
    const retryAfter = headers?.["retry-after"];
    if (retryAfter !== undefined) {
      errorResponse.headers.set("Retry-After", retryAfter);
    }
    return errorResponse;
  }

  const { member, tokens } = apiBody.data;
  const response = NextResponse.json(
    { success: true, data: { member }, error: null } satisfies ApiResponse<{
      member: AuthResult["member"];
    }>,
    { status },
  );

  setSessionCookies(response, tokens);
  if (member.onboarded) {
    setOnboardedCookie(response);
  } else {
    clearOnboardedCookie(response);
  }

  return response;
}
