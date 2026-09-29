import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { components } from "@/shared/api";
import { env } from "@/shared/config";
import { sanitizeNextPath, withNextPath } from "@/shared/lib";
import {
  callApi,
  clearOAuthStateCookie,
  clearOnboardedCookie,
  isOAuthProvider,
  OAUTH_STATE_COOKIE,
  oauthRedirectUri,
  oauthStateSecret,
  resolveClientIp,
  setOnboardedCookie,
  setSessionCookies,
  verifyOAuthCallback,
} from "@/shared/server";

export const dynamic = "force-dynamic";

type AuthResult = components["schemas"]["AuthResult"];

type LoginError = "oauth_cancelled" | "oauth_failed" | "email_registered";

/**
 * 결과와 상관없이 `__Host-ogu_oauth`를 지운다. 콜백 URL에는 인가 코드가
 * 있으므로 캐시하지 않고, 다음 페이지로 Referer가 넘어가지 않게 한다.
 */
function redirect(path: string): NextResponse {
  const response = NextResponse.redirect(new URL(path, env.APP_ORIGIN), 302);
  response.headers.set("Cache-Control", "no-store");
  response.headers.set("Referrer-Policy", "no-referrer");
  clearOAuthStateCookie(response);
  return response;
}

/** `next`는 state를 확인한 뒤에만 넘긴다. 다시 시도하면 그곳으로 돌아간다. */
function loginWithError(error: LoginError, next?: string): NextResponse {
  return redirect(withNextPath(`/login?error=${error}`, next));
}

/**
 * 외부 로그인 콜백(bff-routes.md). 쿠키의 `state`, 제공자, 만료를 먼저
 * 대조하고, 맞을 때만 제공자가 준 결과를 믿는다.
 * - 동의 취소(`error=access_denied`) → `/login?error=oauth_cancelled` (US3-AC4)
 * - API 409 `EMAIL_REGISTERED_WITH_OTHER_METHOD` → `/login?error=email_registered` (US3-AC3)
 * - 그 밖의 실패 → `/login?error=oauth_failed`
 * - 성공 → 세션 쿠키를 심고, 온보딩 전이면 `/onboarding`(US3-AC1), 아니면
 *   `next`(기본 `/home`)로 보낸다(US3-AC2).
 * 쿠키의 `next`는 시작할 때 검증했지만 여기서 한 번 더 `sanitizeNextPath`를
 * 거친다(서명 키가 새거나 규칙이 바뀌어도 외부 주소로 보내지 않기 위해서다).
 * state를 확인한 뒤의 실패와 온보딩 이동에는 검증한 `next`를 남긴다.
 * 토큰은 쿠키로만 전달하고 URL이나 본문에 넣지 않는다(FR-012).
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ provider: string }> },
): Promise<NextResponse> {
  const { provider } = await params;
  if (!isOAuthProvider(provider)) {
    return loginWithError("oauth_failed");
  }

  const query = request.nextUrl.searchParams;
  const stored = verifyOAuthCallback(
    request.cookies.get(OAUTH_STATE_COOKIE)?.value,
    { provider, state: query.get("state") },
    oauthStateSecret(),
  );
  if (stored === null) {
    return loginWithError("oauth_failed");
  }

  const next = sanitizeNextPath(stored.next);

  const providerError = query.get("error");
  if (providerError !== null) {
    return loginWithError(
      providerError === "access_denied" ? "oauth_cancelled" : "oauth_failed",
      next,
    );
  }

  const code = query.get("code");
  if (!code) {
    return loginWithError("oauth_failed", next);
  }

  const { status, body } = await callApi<AuthResult>(
    `/api/v1/auth/oauth/${provider}`,
    resolveClientIp(request),
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        code,
        redirectUri: oauthRedirectUri(provider, env.APP_ORIGIN),
        ...(stored.codeVerifier !== null
          ? { codeVerifier: stored.codeVerifier }
          : {}),
      }),
    },
  );

  if (body === null || !body.success) {
    if (
      status === 409 &&
      body?.error.code === "EMAIL_REGISTERED_WITH_OTHER_METHOD"
    ) {
      return loginWithError("email_registered", next);
    }
    // 시크릿(code, 토큰)은 남기지 않는다.
    console.error(
      `[oauth] ${provider} 로그인 실패: status=${status} code=${body?.error.code ?? "EMPTY_BODY"}`,
    );
    return loginWithError("oauth_failed", next);
  }

  const { member, tokens } = body.data;
  const response = redirect(
    member.onboarded ? next : withNextPath("/onboarding", next),
  );
  setSessionCookies(response, tokens);
  if (member.onboarded) {
    setOnboardedCookie(response);
  } else {
    clearOnboardedCookie(response);
  }
  return response;
}
