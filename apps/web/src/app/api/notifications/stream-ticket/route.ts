import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import type { ApiResponse, components } from "@/shared/api";
import { env } from "@/shared/config";
import {
  applyRefreshedSession,
  callApi,
  callWithSessionRefresh,
  guardOrigin,
  readSessionCookies,
  resolveClientIp,
} from "@/shared/server";

export const dynamic = "force-dynamic";

type StreamTicket = components["schemas"]["StreamTicket"];

interface StreamTicketResponse {
  ticket: string;
  streamUrl: string;
  expiresAt: string;
}

const STREAM_PATH = "/api/v1/notifications/stream";

const FALLBACK_ERROR: ApiResponse<never> = {
  success: false,
  data: null,
  error: { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." },
};

/** 브라우저가 바로 붙을 스트림 주소. `SSE_PUBLIC_ORIGIN`이 없으면 `API_ORIGIN`이다. */
function streamUrl(): string {
  return new URL(
    STREAM_PATH,
    env.SSE_PUBLIC_ORIGIN ?? env.API_ORIGIN,
  ).toString();
}

/**
 * 실시간 알림 스트림의 일회용 티켓을 발급한다(bff-routes.md, 004 research R3).
 *
 * 쿠키의 `ogu_at`으로 API `POST /api/v1/notifications/stream-tickets`를 부르고, 세션 오류
 * 401이면 범용 프록시와 같은 `callWithSessionRefresh` 규칙으로 한 번 갱신한다. 브라우저는
 * 응답의 `streamUrl`에 `ticket`과 `lastEventId`를 붙여 API 도메인에 바로 붙는다. 이 주소가
 * 브라우저가 같은 출처 밖을 부르는 유일한 예외다.
 *
 * 본문에는 `ticket`, `streamUrl`, `expiresAt`만 골라 담는다. access, refresh 토큰은 쿠키로만
 * 오간다(FR-006, FR-012). 범용 프록시는 `notifications/stream-tickets`를 404로 막으므로 티켓은
 * 여기서만 나간다.
 */
export async function POST(request: NextRequest): Promise<NextResponse> {
  const originGuardResponse = guardOrigin(request);
  if (originGuardResponse) {
    return originGuardResponse;
  }

  const clientIp = resolveClientIp(request);
  const outcome = await callWithSessionRefresh(
    readSessionCookies(request),
    clientIp,
    (accessToken) => {
      const headers = new Headers();
      if (accessToken) {
        headers.set("Authorization", `Bearer ${accessToken}`);
      }
      return callApi<StreamTicket>(
        "/api/v1/notifications/stream-tickets",
        clientIp,
        { method: "POST", headers },
      );
    },
  );
  if (outcome.type === "failed") {
    return outcome.response;
  }

  const { result, refreshed } = outcome;
  const response = ticketResponse(result.status, result.body);
  // 갱신으로 받은 토큰은 결과와 관계없이 쿠키에 반영한다(교체된 refresh 토큰을 잃지 않는다).
  if (refreshed !== null) {
    applyRefreshedSession(response, refreshed);
  }
  return response;
}

function ticketResponse(
  status: number,
  apiBody: ApiResponse<StreamTicket> | null,
): NextResponse {
  if (apiBody === null) {
    // 2xx인데 본문이 없으면 계약 위반이다. 그대로 돌려주면 브라우저가 티켓 없는 성공으로 본다.
    const isSuccessStatus = status >= 200 && status < 300;
    return NextResponse.json(FALLBACK_ERROR, {
      status: isSuccessStatus ? 502 : status,
    });
  }
  if (!apiBody.success) {
    return NextResponse.json(apiBody, { status });
  }
  return NextResponse.json(
    {
      success: true,
      data: {
        ticket: apiBody.data.ticket,
        streamUrl: streamUrl(),
        expiresAt: apiBody.data.expiresAt,
      },
      error: null,
    } satisfies ApiResponse<StreamTicketResponse>,
    { status: 200 },
  );
}
