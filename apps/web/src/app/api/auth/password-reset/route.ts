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
 * 비밀번호 재설정 메일 요청 전용 BFF 라우트(015). `/api/[...path]` 캐치올은 `auth/**`를 404로 막는다.
 *
 * origin-guard → 본문과 클라이언트 IP를 그대로 `POST /api/v1/auth/password-reset`에 넘긴다(같은 곳의 한도를
 * API가 IP로 센다). 응답은 상태와 오류 봉투를 그대로 돌려준다. 429의 `Retry-After`도 넘긴다. 토큰을 다루지 않아
 * 쿠키를 건드리지 않는다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const { status, body, headers } = await callApi<never>(
    "/api/v1/auth/password-reset",
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
