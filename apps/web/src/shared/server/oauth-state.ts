import "server-only";

import {
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual,
} from "node:crypto";

import type { NextResponse } from "next/server";

/**
 * 외부 로그인(OAuth) 시작과 콜백 사이에 들고 다니는 상태 (research R4).
 *
 * 시작 라우트가 `state`(CSRF 방지)와 PKCE `code_verifier`(구글만), 검증을 마친
 * `next`를 `__Host-ogu_oauth` 쿠키에 담고, 콜백 라우트가 이 쿠키로 돌아온
 * `state`를 대조한다. 쿠키 값은 `base64url(JSON).base64url(HMAC-SHA256)`이라
 * 서버 비밀 키 없이는 바꿀 수 없다(`next`를 외부 주소로 바꾸는 등). 이 파일은
 * 환경변수를 읽지 않는 순수 함수만 두고, 비밀 키와 출처는 호출하는 쪽이 넘긴다.
 */

export const OAUTH_STATE_COOKIE = "__Host-ogu_oauth";
export const OAUTH_STATE_MAX_AGE_SECONDS = 10 * 60;

const OAUTH_STATE_MAX_AGE_MS = OAUTH_STATE_MAX_AGE_SECONDS * 1000;
const RANDOM_TOKEN_BYTES = 32;
const SIGNATURE_CONTEXT = "ogu_oauth.v1.";

const COOKIE_OPTIONS = {
  httpOnly: true,
  secure: true,
  sameSite: "lax" as const,
  path: "/",
};

export const OAUTH_PROVIDERS = ["kakao", "google"] as const;
export type OAuthProvider = (typeof OAUTH_PROVIDERS)[number];

export interface OAuthStatePayload {
  provider: OAuthProvider;
  state: string;
  /** 구글만 PKCE를 쓴다. 카카오는 PKCE를 지원하지 않아 null이다. */
  codeVerifier: string | null;
  /** `sanitizeNextPath`(shared/lib)를 통과한 같은 출처 경로. */
  next: string;
  /** 발급 시각(epoch ms). */
  issuedAt: number;
}

export function isOAuthProvider(value: string): value is OAuthProvider {
  return (OAUTH_PROVIDERS as readonly string[]).includes(value);
}

/** 32바이트 난수를 패딩 없는 base64url(43자)로 만든다. */
export function generateRandomToken(): string {
  return randomBytes(RANDOM_TOKEN_BYTES).toString("base64url");
}

/** PKCE S256: `base64url(SHA-256(code_verifier))` (RFC 7636). */
export function codeChallengeS256(codeVerifier: string): string {
  return createHash("sha256").update(codeVerifier).digest("base64url");
}

export function createOAuthState(
  provider: OAuthProvider,
  next: string,
  now: number = Date.now(),
): OAuthStatePayload {
  return {
    provider,
    state: generateRandomToken(),
    codeVerifier: provider === "google" ? generateRandomToken() : null,
    next,
    issuedAt: now,
  };
}

function sign(body: string, secret: string): string {
  return createHmac("sha256", secret)
    .update(SIGNATURE_CONTEXT + body)
    .digest("base64url");
}

function safeEqual(a: string, b: string): boolean {
  const left = Buffer.from(a);
  const right = Buffer.from(b);
  return left.length === right.length && timingSafeEqual(left, right);
}

export function serializeOAuthState(
  payload: OAuthStatePayload,
  secret: string,
): string {
  const body = Buffer.from(JSON.stringify(payload)).toString("base64url");
  return `${body}.${sign(body, secret)}`;
}

function parsePayload(json: string): OAuthStatePayload | null {
  let value: unknown;
  try {
    value = JSON.parse(json);
  } catch {
    return null;
  }
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const candidate = value as Record<string, unknown>;
  const { provider, state, codeVerifier, next, issuedAt } = candidate;
  if (
    typeof provider !== "string" ||
    !isOAuthProvider(provider) ||
    typeof state !== "string" ||
    state.length === 0 ||
    !(codeVerifier === null || typeof codeVerifier === "string") ||
    typeof next !== "string" ||
    typeof issuedAt !== "number" ||
    !Number.isFinite(issuedAt)
  ) {
    return null;
  }
  return { provider, state, codeVerifier, next, issuedAt };
}

/**
 * 콜백으로 돌아온 `state`와 제공자를 쿠키와 대조한다. 서명이 맞고, 10분이
 * 지나지 않았고, 제공자와 `state`가 모두 같을 때만 쿠키에 담았던 값을
 * 돌려준다. 하나라도 어긋나면 null이다. `state`는 상수 시간으로 비교한다.
 */
export function verifyOAuthCallback(
  cookieValue: string | undefined,
  callback: { provider: OAuthProvider; state: string | null },
  secret: string,
  now: number = Date.now(),
): OAuthStatePayload | null {
  if (!cookieValue || !callback.state) {
    return null;
  }
  const parts = cookieValue.split(".");
  if (parts.length !== 2) {
    return null;
  }
  const [body, signature] = parts;
  if (!body || !signature || !safeEqual(signature, sign(body, secret))) {
    return null;
  }

  const payload = parsePayload(Buffer.from(body, "base64url").toString("utf8"));
  if (payload === null) {
    return null;
  }
  const age = now - payload.issuedAt;
  if (age < 0 || age > OAUTH_STATE_MAX_AGE_MS) {
    return null;
  }
  if (payload.provider !== callback.provider) {
    return null;
  }
  if (!safeEqual(payload.state, callback.state)) {
    return null;
  }
  return payload;
}

/** `APP_ORIGIN + /api/auth/oauth/{provider}/callback`. API 허용 목록과 같아야 한다. */
export function oauthRedirectUri(
  provider: OAuthProvider,
  appOrigin: string,
): string {
  return new URL(`/api/auth/oauth/${provider}/callback`, appOrigin).href;
}

/**
 * 제공자 인가 URL. 카카오는 `account_email`(선택 동의)만 요청하고 PKCE를
 * 지원하지 않아 `state`만 쓴다. 구글은 `openid email`과 PKCE S256을 쓴다.
 */
export function buildAuthorizationUrl(
  payload: OAuthStatePayload,
  options: { clientId: string; redirectUri: string },
): string {
  const url =
    payload.provider === "kakao"
      ? new URL("https://kauth.kakao.com/oauth/authorize")
      : new URL("https://accounts.google.com/o/oauth2/v2/auth");
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", options.clientId);
  url.searchParams.set("redirect_uri", options.redirectUri);
  url.searchParams.set("state", payload.state);

  if (payload.provider === "kakao") {
    url.searchParams.set("scope", "account_email");
  } else {
    if (payload.codeVerifier === null) {
      throw new Error(
        "oauth-state: 구글 인가 요청에는 code_verifier가 있어야 한다",
      );
    }
    url.searchParams.set("scope", "openid email");
    url.searchParams.set(
      "code_challenge",
      codeChallengeS256(payload.codeVerifier),
    );
    url.searchParams.set("code_challenge_method", "S256");
  }
  return url.href;
}

export function setOAuthStateCookie(
  response: NextResponse,
  value: string,
): void {
  response.cookies.set(OAUTH_STATE_COOKIE, value, {
    ...COOKIE_OPTIONS,
    maxAge: OAUTH_STATE_MAX_AGE_SECONDS,
  });
}

/** 콜백은 결과와 상관없이 이 쿠키를 지운다(같은 state를 두 번 쓰지 못하게). */
export function clearOAuthStateCookie(response: NextResponse): void {
  response.cookies.set(OAUTH_STATE_COOKIE, "", {
    ...COOKIE_OPTIONS,
    maxAge: 0,
  });
}
