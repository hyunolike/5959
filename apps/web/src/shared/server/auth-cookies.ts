import "server-only";

import type { NextRequest, NextResponse } from "next/server";

/**
 * 브라우저 쿠키 규칙 (research R3).
 * 모두 HttpOnly, Secure, SameSite=Lax, Path=/. `__Host-` 접두사를 쓰면
 * 하위 도메인이 쿠키를 덮어쓸 수 없다.
 */
export const ACCESS_TOKEN_COOKIE = "__Host-ogu_at";
export const REFRESH_TOKEN_COOKIE = "__Host-ogu_rt";
export const ONBOARDED_COOKIE = "__Host-ogu_ob";

const ACCESS_TOKEN_MAX_AGE_SECONDS = 15 * 60;

const BASE_COOKIE_OPTIONS = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  path: "/",
};

export interface SessionTokens {
  accessToken: string;
  /** refresh 유예 구간(교체 후 30초)에서는 null이다. 이때 ogu_rt는 건드리지 않는다. */
  refreshToken: string | null;
  /** ISO 8601. refresh 토큰 쿠키의 Max-Age를 이 시각까지 남은 초로 계산한다. */
  refreshTokenExpiresAt: string;
}

export interface SessionCookies {
  accessToken: string | undefined;
  refreshToken: string | undefined;
  onboarded: boolean;
}

function refreshTokenMaxAgeSeconds(refreshTokenExpiresAt: string): number {
  const expiresAtMs = new Date(refreshTokenExpiresAt).getTime();
  if (Number.isNaN(expiresAtMs)) {
    console.error(
      `[auth-cookies] refreshTokenExpiresAt을 해석할 수 없어 쿠키를 바로 지운다: "${refreshTokenExpiresAt}"`,
    );
    return 0;
  }
  const remainingMs = expiresAtMs - Date.now();
  return Math.max(0, Math.round(remainingMs / 1000));
}

/**
 * 로그인, 가입, 세션 갱신 뒤 세션 쿠키를 심는다.
 * `refreshToken`이 null이면(refresh 유예 구간) `ogu_rt`는 바꾸지 않는다.
 */
export function setSessionCookies(
  response: NextResponse,
  tokens: SessionTokens,
): void {
  response.cookies.set(ACCESS_TOKEN_COOKIE, tokens.accessToken, {
    ...BASE_COOKIE_OPTIONS,
    maxAge: ACCESS_TOKEN_MAX_AGE_SECONDS,
  });

  if (tokens.refreshToken !== null) {
    response.cookies.set(REFRESH_TOKEN_COOKIE, tokens.refreshToken, {
      ...BASE_COOKIE_OPTIONS,
      maxAge: refreshTokenMaxAgeSeconds(tokens.refreshTokenExpiresAt),
    });
  }
}

/** 온보딩을 마쳤다는 표시 쿠키를 심는다. */
export function setOnboardedCookie(response: NextResponse): void {
  response.cookies.set(ONBOARDED_COOKIE, "1", BASE_COOKIE_OPTIONS);
}

/** 요청에 실려 온 세션 쿠키를 읽는다. */
export function readSessionCookies(request: NextRequest): SessionCookies {
  return {
    accessToken: request.cookies.get(ACCESS_TOKEN_COOKIE)?.value,
    refreshToken: request.cookies.get(REFRESH_TOKEN_COOKIE)?.value,
    onboarded: request.cookies.get(ONBOARDED_COOKIE)?.value === "1",
  };
}

/** 로그아웃 등에서 세 쿠키를 전부 지운다. */
export function clearSessionCookies(response: NextResponse): void {
  for (const name of [
    ACCESS_TOKEN_COOKIE,
    REFRESH_TOKEN_COOKIE,
    ONBOARDED_COOKIE,
  ]) {
    response.cookies.set(name, "", { ...BASE_COOKIE_OPTIONS, maxAge: 0 });
  }
}
