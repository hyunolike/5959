import { randomUUID } from "node:crypto";

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { env } from "@/shared/config";
import {
  buildAuthorizationUrl,
  createOAuthState,
  isOAuthProvider,
  oauthRedirectUri,
  oauthStateSecret,
  sanitizeNextPath,
  serializeOAuthState,
  setOAuthStateCookie,
  type OAuthProvider,
  type OAuthStatePayload,
} from "@/shared/server";

export const dynamic = "force-dynamic";

/** 가짜 제공자 코드(`fake:<id>:<email>`)의 구분자를 깨지 않는 값만 받는다. */
const E2E_ID_PATTERN = /^[A-Za-z0-9_-]{1,64}$/;
const E2E_EMAIL_PATTERN = /^[^\s:@]{1,64}@[^\s:@]{1,255}$/;

function redirect(to: string | URL): NextResponse {
  const response = NextResponse.redirect(to, 302);
  response.headers.set("Cache-Control", "no-store");
  return response;
}

/**
 * APP_ENV=e2e 전용. 제공자에 가지 않고 자기 콜백으로 바로 보낸다. API의
 * e2e 프로필(FakeOAuthProviderClient)이 `fake:<id>:<email 또는 ->` 코드를
 * 받아 사용자를 만든다. 테스트는 쿼리로 결과를 고른다.
 * - `e2e_id`: 제공자 사용자 ID(없으면 무작위). 같은 값이면 같은 계정이다.
 * - `e2e_email`: 제공자가 준 이메일(없으면 이메일 동의 안 함).
 * - `e2e_outcome=cancel`: 사용자가 동의를 취소함(`error=access_denied`).
 * - `e2e_outcome=denied`: 제공자가 코드를 거절함(`code=denied`).
 */
function fakeProviderCallbackUrl(
  request: NextRequest,
  payload: OAuthStatePayload,
): URL {
  const query = request.nextUrl.searchParams;
  const url = new URL(oauthRedirectUri(payload.provider, env.APP_ORIGIN));
  const outcome = query.get("e2e_outcome");

  if (outcome === "cancel") {
    url.searchParams.set("error", "access_denied");
  } else if (outcome === "denied") {
    url.searchParams.set("code", "denied");
  } else {
    const idParam = query.get("e2e_id");
    const emailParam = query.get("e2e_email");
    const id =
      idParam !== null && E2E_ID_PATTERN.test(idParam) ? idParam : randomUUID();
    const email =
      emailParam !== null && E2E_EMAIL_PATTERN.test(emailParam)
        ? emailParam
        : "-";
    url.searchParams.set("code", `fake:${id}:${email}`);
  }
  url.searchParams.set("state", payload.state);
  return url;
}

function clientIdFor(provider: OAuthProvider): string | undefined {
  return provider === "kakao" ? env.KAKAO_CLIENT_ID : env.GOOGLE_CLIENT_ID;
}

/**
 * 외부 로그인 시작(bff-routes.md). `state`와 PKCE `code_verifier`(구글만),
 * 검증한 `next`를 서명한 `__Host-ogu_oauth` 쿠키(10분)에 담고 제공자 인가
 * URL로 302 리다이렉트한다. 지원하지 않는 제공자면 `/login`으로 보낸다.
 */
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ provider: string }> },
): Promise<NextResponse> {
  const { provider } = await params;
  if (!isOAuthProvider(provider)) {
    return redirect(new URL("/login", env.APP_ORIGIN));
  }

  const payload = createOAuthState(
    provider,
    sanitizeNextPath(request.nextUrl.searchParams.get("next")),
  );

  let target: string | URL;
  if (env.APP_ENV === "e2e") {
    target = fakeProviderCallbackUrl(request, payload);
  } else {
    const clientId = clientIdFor(provider);
    if (!clientId) {
      console.error(
        `[oauth] ${provider} client id가 설정되지 않아 외부 로그인을 시작할 수 없다`,
      );
      return redirect(new URL("/login?error=oauth_failed", env.APP_ORIGIN));
    }
    target = buildAuthorizationUrl(payload, {
      clientId,
      redirectUri: oauthRedirectUri(provider, env.APP_ORIGIN),
    });
  }

  const response = redirect(target);
  setOAuthStateCookie(
    response,
    serializeOAuthState(payload, oauthStateSecret()),
  );
  return response;
}
