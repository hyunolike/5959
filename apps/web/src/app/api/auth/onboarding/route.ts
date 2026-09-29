import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse, components } from "@/shared/api";
import {
  applyRefreshedSession,
  callApi,
  callWithSessionRefresh,
  guardOrigin,
  readSessionCookies,
  resolveClientIp,
  setOnboardedCookie,
  setSessionCookies,
  type RefreshedSession,
} from "@/shared/server";

export const dynamic = "force-dynamic";

type OnboardingResult = components["schemas"]["OnboardingResult"];

const FALLBACK_ERROR: ApiResponse<never> = {
  success: false,
  data: null,
  error: { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." },
};

/**
 * 온보딩 완료 전용 BFF 라우트(bff-routes.md). `/api/[...path]` 캐치올은
 * `members/me/onboarding`을 404로 막으므로(FR-012, 새 accessToken 노출 방지),
 * 여기서만 다룬다.
 *
 * origin-guard → `ogu_at`을 Authorization으로 바꿔 api-client 호출 → 성공하면
 * `ogu_at`을 새 토큰으로 교체하고(`ogu_rt`는 그대로 둔다) `ogu_ob`를 설정한다
 * → 본문에는 `member`만 남긴다. 실패(400, 401, 409)는 API의 오류 봉투를
 * 그대로 전달한다.
 *
 * 온보딩 화면에 오래 머물러 access 토큰이 만료돼도 끝낼 수 있게, 범용 프록시와
 * 같은 `callWithSessionRefresh` 규칙으로 refresh한다(US4-AC1). refresh로 받은
 * 토큰은 결과와 관계없이 쿠키에 반영한다(교체된 refresh 토큰을 잃지 않기 위해서다).
 */
export async function PUT(request: NextRequest): Promise<NextResponse> {
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
      return callApi<OnboardingResult>(
        "/api/v1/members/me/onboarding",
        clientIp,
        { method: "PUT", headers, body },
      );
    },
  );
  if (outcome.type === "failed") {
    return outcome.response;
  }
  return onboardingResponse(
    outcome.result.status,
    outcome.result.body,
    outcome.refreshed,
  );
}

/**
 * refresh로 받은 토큰은 결과와 관계없이 먼저 쿠키에 반영한다(교체된 refresh
 * 토큰을 잃지 않기 위해서다). 온보딩에 성공하면 그 뒤에 온보딩 결과의 access
 * 토큰과 `ogu_ob`로 덮는다.
 */
function onboardingResponse(
  status: number,
  apiBody: ApiResponse<OnboardingResult> | null,
  refreshed: RefreshedSession | null,
): NextResponse {
  const withRefreshed = (response: NextResponse) => {
    if (refreshed !== null) {
      applyRefreshedSession(response, refreshed);
    }
    return response;
  };
  if (apiBody === null) {
    // apps/api가 2xx인데 본문이 비어 있는 건 계약 위반이다 — 그 2xx 상태를
    // 그대로 돌려주면 브라우저가 온보딩에 성공한 줄 알게 된다.
    const isSuccessStatus = status >= 200 && status < 300;
    return withRefreshed(
      NextResponse.json(FALLBACK_ERROR, {
        status: isSuccessStatus ? 502 : status,
      }),
    );
  }
  if (!apiBody.success) {
    return withRefreshed(NextResponse.json(apiBody, { status }));
  }

  const { member, accessToken: newAccessToken } = apiBody.data;
  const response = withRefreshed(
    NextResponse.json(
      { success: true, data: { member }, error: null } satisfies ApiResponse<{
        member: OnboardingResult["member"];
      }>,
      { status },
    ),
  );

  // OnboardingResult에는 refresh 토큰이 없다 — refreshToken: null로 넘겨
  // ogu_rt는 건드리지 않는다(setSessionCookies의 유예 구간 처리를 재사용).
  setSessionCookies(response, {
    accessToken: newAccessToken,
    refreshToken: null,
    refreshTokenExpiresAt: "",
  });
  setOnboardedCookie(response);

  return response;
}
