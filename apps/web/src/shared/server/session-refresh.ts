import "server-only";

import { NextResponse } from "next/server";

import type { ApiResponse, components } from "@/shared/api";

import { callApi, type ApiClientResult } from "./api-client";
import {
  clearOnboardedCookie,
  clearSessionCookies,
  setOnboardedCookie,
  setSessionCookies,
  type SessionCookies,
  type SessionTokens,
} from "./auth-cookies";

type AuthResult = components["schemas"]["AuthResult"];

export type SessionRefreshResult =
  | { type: "refreshed"; tokens: SessionTokens; onboarded: boolean }
  /** API가 refresh 토큰을 거절했다(401 등 4xx). 세션을 더 쓸 수 없다. */
  | { type: "rejected" }
  /** API에 닿지 못했거나 5xx다. 세션이 끝났는지 알 수 없으므로 쿠키를 지우지 않는다. */
  | { type: "unavailable"; status: number; body: ApiResponse<unknown> | null };

/**
 * `POST /api/v1/auth/refresh`를 부른다(bff-routes.md, research R2). 응답
 * 본문의 토큰은 호출한 쪽이 [applyRefreshedSession]으로 쿠키에만 옮긴다.
 */
export async function refreshSession(
  refreshToken: string,
  clientIp: string,
): Promise<SessionRefreshResult> {
  const { status, body } = await callApi<AuthResult>(
    "/api/v1/auth/refresh",
    clientIp,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ refreshToken }),
    },
  );

  if (body !== null && body.success) {
    return {
      type: "refreshed",
      tokens: body.data.tokens,
      onboarded: body.data.member.onboarded,
    };
  }
  if (status >= 400 && status < 500) {
    return { type: "rejected" };
  }
  // 2xx인데 본문이 없거나 성공 봉투가 아니면 계약 위반이라 장애로 본다.
  const isSuccessStatus = status >= 200 && status < 300;
  return {
    type: "unavailable",
    status: isSuccessStatus ? 502 : status,
    body: isSuccessStatus ? null : body,
  };
}

/**
 * 갱신한 토큰을 쿠키로 심는다. `refreshToken`이 null(유예 구간)이면 `ogu_rt`는
 * 그대로 두고, 온보딩 여부에 맞춰 `ogu_ob`를 설정하거나 지운다.
 */
export function applyRefreshedSession(
  response: NextResponse,
  refreshed: Extract<SessionRefreshResult, { type: "refreshed" }>,
): void {
  setSessionCookies(response, refreshed.tokens);
  if (refreshed.onboarded) {
    setOnboardedCookie(response, refreshed.tokens.refreshTokenExpiresAt);
  } else {
    clearOnboardedCookie(response);
  }
}

/** 세션이 끝났다는 401 응답을 채운다. 세 쿠키를 모두 지운다. */
function endSession(response: NextResponse): NextResponse {
  clearSessionCookies(response);
  return response;
}

const SESSION_EXPIRED_BODY: ApiResponse<never> = {
  success: false,
  data: null,
  error: {
    code: "SESSION_EXPIRED",
    message: "세션이 만료되었습니다. 다시 로그인해 주세요.",
  },
};

export type RefreshedSession = Extract<
  SessionRefreshResult,
  { type: "refreshed" }
>;

export type SessionCallOutcome<T> =
  /** API 응답. `refreshed`가 있으면 호출한 쪽이 응답에 쿠키로 반영해야 한다. */
  | {
      type: "result";
      result: ApiClientResult<T>;
      refreshed: RefreshedSession | null;
    }
  /** 세션을 쓸 수 없거나 갱신하지 못했다. 이 응답을 그대로 돌려준다. */
  | { type: "failed"; response: NextResponse };

/** access 토큰이 만료, 무효라서 난 401만 refresh 대상이다. */
const SESSION_ERROR_CODES = new Set(["UNAUTHORIZED", "SESSION_EXPIRED"]);

function isSessionError(result: ApiClientResult<unknown>): boolean {
  return (
    result.status === 401 &&
    result.body !== null &&
    !result.body.success &&
    SESSION_ERROR_CODES.has(result.body.error.code)
  );
}

function sessionExpiredResponse(): NextResponse {
  return endSession(NextResponse.json(SESSION_EXPIRED_BODY, { status: 401 }));
}

function refreshFailureResponse(
  outcome: Exclude<SessionRefreshResult, { type: "refreshed" }>,
): NextResponse {
  if (outcome.type === "rejected") {
    return sessionExpiredResponse();
  }
  return outcome.body === null
    ? new NextResponse(null, { status: outcome.status })
    : NextResponse.json(outcome.body, { status: outcome.status });
}

/**
 * 세션이 필요한 API 호출을 refresh 규칙과 함께 한다(bff-routes.md 범용 프록시,
 * US4-AC1). `send`는 받은 access 토큰으로 요청을 한 번 보내는 함수다.
 *
 * - `ogu_at`이 없고 `ogu_rt`만 있으면 먼저 refresh한다.
 * - API가 세션 오류 401(`UNAUTHORIZED`, `SESSION_EXPIRED`)이면 `ogu_rt`로 한 번
 *   refresh하고 한 번만 다시 보낸다. 다른 401 코드는 그대로 돌려준다.
 * - 요청 하나에서 refresh는 많아야 한 번이다. 갱신한 토큰으로도 세션 오류면
 *   세 쿠키를 지우고 401 `SESSION_EXPIRED`다(갱신 반복 방지).
 * - refresh가 거절되면(4xx) 세 쿠키를 지우고 401, API 장애(5xx, 연결 실패)면
 *   쿠키를 지우지 않고 그 오류를 돌려준다.
 */
export async function callWithSessionRefresh<T>(
  cookies: SessionCookies,
  clientIp: string,
  send: (accessToken: string | undefined) => Promise<ApiClientResult<T>>,
): Promise<SessionCallOutcome<T>> {
  let refreshed: RefreshedSession | null = null;

  if (!cookies.accessToken && cookies.refreshToken) {
    const outcome = await refreshSession(cookies.refreshToken, clientIp);
    if (outcome.type !== "refreshed") {
      return { type: "failed", response: refreshFailureResponse(outcome) };
    }
    refreshed = outcome;
  }

  let result = await send(refreshed?.tokens.accessToken ?? cookies.accessToken);
  if (!isSessionError(result) || !cookies.refreshToken) {
    return { type: "result", result, refreshed };
  }
  if (refreshed !== null) {
    return { type: "failed", response: sessionExpiredResponse() };
  }

  const outcome = await refreshSession(cookies.refreshToken, clientIp);
  if (outcome.type !== "refreshed") {
    return { type: "failed", response: refreshFailureResponse(outcome) };
  }
  result = await send(outcome.tokens.accessToken);
  if (isSessionError(result)) {
    return { type: "failed", response: sessionExpiredResponse() };
  }
  return { type: "result", result, refreshed: outcome };
}
