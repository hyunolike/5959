import "server-only";

import type { NextResponse } from "next/server";

import type { ApiResponse, components } from "@/shared/api";

import { callApi } from "./api-client";
import {
  clearOnboardedCookie,
  clearSessionCookies,
  setOnboardedCookie,
  setSessionCookies,
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
    setOnboardedCookie(response);
  } else {
    clearOnboardedCookie(response);
  }
}

/** 세션이 끝났다는 401 응답을 채운다. 세 쿠키를 모두 지운다. */
export function endSession(response: NextResponse): NextResponse {
  clearSessionCookies(response);
  return response;
}

export const SESSION_EXPIRED_BODY: ApiResponse<never> = {
  success: false,
  data: null,
  error: {
    code: "SESSION_EXPIRED",
    message: "세션이 만료되었습니다. 다시 로그인해 주세요.",
  },
};
