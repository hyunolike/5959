import { NextRequest, NextResponse } from "next/server";

import type { ApiResponse } from "@/shared/api";
import {
  applyRefreshedSession,
  callApi,
  callWithSessionRefresh,
  guardOrigin,
  readSessionCookies,
  resolveClientIp,
} from "@/shared/server";

export const dynamic = "force-dynamic";

interface RouteParams {
  path: string[];
}

interface RouteContext {
  params: Promise<RouteParams>;
}

const METHODS_WITHOUT_BODY = new Set(["GET", "HEAD"]);
const UNSAFE_SEGMENT_PATTERN = /[/\\?#]/;

/**
 * 로그인, 가입, refresh, OAuth, 온보딩은 전용 BFF 라우트에서만 다룬다(API
 * 응답의 accessToken/refreshToken을 쿠키로 바꾸고 본문에서 지우는 역할).
 * 이 캐치올이 그대로 넘기면 토큰이 응답 본문에 그대로 실려 브라우저
 * 스크립트에 노출된다(FR-012, bff-routes.md).
 */
const DENIED_TOP_LEVEL_SEGMENTS = new Set(["auth"]);
const DENIED_EXACT_PATHS = new Set(["members/me/onboarding"]);

function isSafeSegmentValue(segment: string): boolean {
  return (
    segment.length > 0 &&
    segment !== "." &&
    segment !== ".." &&
    !UNSAFE_SEGMENT_PATTERN.test(segment)
  );
}

/**
 * Next.js는 캐치올 경로를 "/"로 먼저 나눈 뒤 각 조각을 퍼센트 디코딩해서
 * `path` 배열을 만든다. 그래서 이중으로 인코딩된 값(`%2F`, `%2E%2E`,
 * `auth%2Flogin` 등)은 디코딩 한 번을 더 거쳐야 진짜 모습(슬래시, `..`)이
 * 드러난다. 원래 값과 한 번 더 디코딩한 값을 모두 검사해서 `.`, `..`, `/`,
 * `\`, `?`, `#`이 숨어 있지 않은지 확인한다.
 */
function isValidSegment(segment: string): boolean {
  if (!isSafeSegmentValue(segment)) {
    return false;
  }
  try {
    return isSafeSegmentValue(decodeURIComponent(segment));
  } catch {
    return false;
  }
}

function isDeniedPath(segments: string[]): boolean {
  if (segments.length === 0) {
    return true;
  }
  // 대소문자를 구분하지 않고 비교한다 — Next.js 라우팅과 apps/api 둘 다
  // 경로를 대소문자 그대로 다루므로, `/api/Auth/login`처럼 대문자가 섞인
  // 변형도 같은 전용 라우트 대상이다.
  if (DENIED_TOP_LEVEL_SEGMENTS.has(segments[0].toLowerCase())) {
    return true;
  }
  return DENIED_EXACT_PATHS.has(segments.join("/").toLowerCase());
}

function notFoundResponse(): NextResponse {
  const body: ApiResponse<never> = {
    success: false,
    data: null,
    error: { code: "NOT_FOUND", message: "요청한 경로를 찾을 수 없습니다." },
  };
  return NextResponse.json(body, { status: 404 });
}

/**
 * 범용 BFF 프록시: `/api/{path}`를 `API_ORIGIN/api/v1/{path}`로 그대로
 * 전달한다. `__Host-ogu_at`을 `Authorization: Bearer`로 바꾸고, 상태를
 * 바꾸는 요청에는 origin-guard를 적용한다. 세그먼트를 검증하고
 * `encodeURIComponent`로 다시 인코딩한 뒤 경로 접두사를 한 번 더 확인해
 * 경로 조작(`..`, 인코딩된 `/`)을 막고, `auth/**`와 `members/me/onboarding`은
 * 전용 라우트만 다루므로 404로 막는다.
 *
 * 세션 갱신(US4-AC1)은 `callWithSessionRefresh`(shared/server/session-refresh.ts)
 * 규칙을 따른다: `ogu_at`이 없고 `ogu_rt`만 있으면 먼저 refresh하고, API가
 * 세션 오류 401이면 한 번 refresh한 뒤 원래 요청을 한 번만 다시 보낸다.
 */
async function proxy(
  request: NextRequest,
  { params }: RouteContext,
): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const { path } = await params;

  if (!path.every(isValidSegment) || isDeniedPath(path)) {
    return notFoundResponse();
  }

  const upstreamPath = `/api/v1/${path.map(encodeURIComponent).join("/")}`;
  const upstreamUrl = new URL(
    `${upstreamPath}${request.nextUrl.search}`,
    "http://upstream.invalid",
  );
  if (!upstreamUrl.pathname.startsWith("/api/v1/")) {
    return notFoundResponse();
  }

  const contentType = request.headers.get("content-type");
  const body = METHODS_WITHOUT_BODY.has(request.method)
    ? undefined
    : await request.text();
  const clientIp = resolveClientIp(request);
  const send = (accessToken: string | undefined) => {
    const headers = new Headers();
    if (contentType) {
      headers.set("content-type", contentType);
    }
    if (accessToken) {
      headers.set("Authorization", `Bearer ${accessToken}`);
    }
    return callApi<unknown>(
      `${upstreamUrl.pathname}${upstreamUrl.search}`,
      clientIp,
      { method: request.method, headers, body },
    );
  };

  const outcome = await callWithSessionRefresh(
    readSessionCookies(request),
    clientIp,
    send,
  );
  if (outcome.type === "failed") {
    return outcome.response;
  }

  const { result, refreshed } = outcome;
  const response =
    result.body === null
      ? new NextResponse(null, { status: result.status })
      : NextResponse.json(result.body satisfies ApiResponse<unknown>, {
          status: result.status,
        });
  if (refreshed !== null) {
    applyRefreshedSession(response, refreshed);
  }
  return response;
}

export {
  proxy as DELETE,
  proxy as GET,
  proxy as PATCH,
  proxy as POST,
  proxy as PUT,
};
