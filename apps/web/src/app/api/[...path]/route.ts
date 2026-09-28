import { NextRequest, NextResponse } from "next/server";

import type { ApiResponse } from "@/shared/api";
import {
  callApi,
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

/**
 * 범용 BFF 프록시: `/api/{path}`를 `API_ORIGIN/api/v1/{path}`로 그대로
 * 전달한다. `__Host-ogu_at`을 `Authorization: Bearer`로 바꾸고, 상태를
 * 바꾸는 요청에는 origin-guard를 적용한다.
 * refresh 재시도(401일 때 ogu_rt로 갱신 후 재요청)는 US4(T058)에서 추가한다.
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
  const upstreamUrl = `/api/v1/${path.join("/")}${request.nextUrl.search}`;

  const headers = new Headers();
  const contentType = request.headers.get("content-type");
  if (contentType) {
    headers.set("content-type", contentType);
  }

  const { accessToken } = readSessionCookies(request);
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }

  const body = METHODS_WITHOUT_BODY.has(request.method)
    ? undefined
    : await request.text();

  const { status, body: responseBody } = await callApi<unknown>(
    upstreamUrl,
    resolveClientIp(request),
    { method: request.method, headers, body },
  );

  return NextResponse.json(responseBody satisfies ApiResponse<unknown>, {
    status,
  });
}

export {
  proxy as DELETE,
  proxy as GET,
  proxy as PATCH,
  proxy as POST,
  proxy as PUT,
};
