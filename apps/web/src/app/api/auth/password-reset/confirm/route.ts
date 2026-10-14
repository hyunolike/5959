import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse } from "@/shared/api";
import { callApi, guardOrigin, resolveClientIp } from "@/shared/server";

export const dynamic = "force-dynamic";

const FALLBACK_ERROR: ApiResponse<never> = {
  success: false,
  data: null,
  error: { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." },
};

/**
 * 새 비밀번호 저장 전용 BFF 라우트(015). 본문(링크의 값과 새 비밀번호)과 클라이언트 IP를
 * `POST /api/v1/auth/password-reset/confirm`에 넘긴다. API는 성공해도 로그인시키지 않으므로(토큰을 주지 않는다)
 * 쿠키를 건드리지 않는다. 다른 회원으로 로그인해 있던 브라우저도 그 로그인은 그대로다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const { status, body, headers } = await callApi<never>(
    "/api/v1/auth/password-reset/confirm",
    resolveClientIp(request),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: await request.text(),
      forwardResponseHeaders: ["Retry-After"],
    },
  );
  if (status === 204) {
    return new NextResponse(null, { status: 204 });
  }
  // 2xx인데 204가 아니면 계약 위반이다. 성공으로 보이면 화면이 메일을 보낸 줄 안다.
  const isSuccessStatus = status >= 200 && status < 300;
  return NextResponse.json(body ?? FALLBACK_ERROR, {
    status: isSuccessStatus ? 502 : status,
    headers,
  });
}
