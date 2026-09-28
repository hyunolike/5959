import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse, components } from "@/shared/api";
import {
  callApi,
  clearOnboardedCookie,
  guardOrigin,
  resolveClientIp,
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
 * 이메일 가입 전용 BFF 라우트(bff-routes.md). `/api/[...path]` 캐치올은
 * `auth/**`를 전부 404로 막으므로(FR-012), 여기서만 다룬다.
 *
 * origin-guard → api-client 호출 → 성공하면 토큰을 `ogu_at`, `ogu_rt` 쿠키로
 * 바꾸고 `ogu_ob`는 지운다(새 회원은 온보딩 전이다) → 본문에는 `member`만
 * 남긴다. 실패(400, 409)는 API의 오류 봉투를 그대로 전달한다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const body = await request.text();
  const { status, body: apiBody } = await callApi<AuthResult>(
    "/api/v1/auth/signup",
    resolveClientIp(request),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body,
    },
  );

  if (apiBody === null || !apiBody.success) {
    return NextResponse.json(apiBody ?? FALLBACK_ERROR, { status });
  }

  const { member, tokens } = apiBody.data;
  const response = NextResponse.json(
    { success: true, data: { member }, error: null } satisfies ApiResponse<{
      member: AuthResult["member"];
    }>,
    { status },
  );

  setSessionCookies(response, tokens);
  clearOnboardedCookie(response);

  return response;
}
