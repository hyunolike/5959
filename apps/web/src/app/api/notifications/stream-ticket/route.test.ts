// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

const { env } = vi.hoisted(() => ({
  env: {
    API_ORIGIN: "http://api.internal:8080",
    BFF_API_KEY: "test-bff-key",
    APP_ORIGIN: "http://localhost:3000",
    SSE_PUBLIC_ORIGIN: undefined as string | undefined,
  },
}));
vi.mock("@/shared/config", () => ({ env }));

import {
  ACCESS_TOKEN_COOKIE,
  ONBOARDED_COOKIE,
  REFRESH_TOKEN_COOKIE,
} from "@/shared/server";

import { POST } from "./route";

const API_TICKET_URL =
  "http://api.internal:8080/api/v1/notifications/stream-tickets";
const API_REFRESH_URL = "http://api.internal:8080/api/v1/auth/refresh";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const ticketBody = (ticket = "ticket-abc") => ({
  success: true,
  data: { ticket, expiresAt: "2026-10-06T00:00:30Z" },
  error: null,
});

const errorBody = (code: string) => ({
  success: false,
  data: null,
  error: { code, message: "오류" },
});

function refreshedBody() {
  return {
    success: true,
    data: {
      member: { id: 1, onboarded: true },
      tokens: {
        accessToken: "new-access",
        accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
        refreshToken: "new-refresh",
        refreshTokenExpiresAt: new Date(
          Date.now() + 14 * 86_400_000,
        ).toISOString(),
      },
      newMember: false,
    },
    error: null,
  };
}

function ticketRequest(
  cookies: Record<string, string> = {},
  origin: string | null = "http://localhost:3000",
) {
  const headers: Record<string, string> = {
    cookie: Object.entries(cookies)
      .map(([name, value]) => `${name}=${value}`)
      .join("; "),
  };
  if (origin !== null) {
    headers.origin = origin;
  }
  return new NextRequest(
    "http://localhost:3000/api/notifications/stream-ticket",
    { method: "POST", headers },
  );
}

function authorizationOf(call: unknown[]): string | null {
  return ((call[1] as RequestInit).headers as Headers).get("Authorization");
}

afterEach(() => {
  vi.unstubAllGlobals();
  env.SSE_PUBLIC_ORIGIN = undefined;
});

describe("POST /api/notifications/stream-ticket", () => {
  it("US1-AC8 쿠키의 access 토큰으로 API에서 티켓을 받아 200으로 돌려준다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(ticketBody(), 201));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      success: true,
      data: {
        ticket: "ticket-abc",
        streamUrl: "http://api.internal:8080/api/v1/notifications/stream",
        expiresAt: "2026-10-06T00:00:30Z",
      },
      error: null,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(API_TICKET_URL);
    expect(init.method).toBe("POST");
    expect(authorizationOf(fetchMock.mock.calls[0])).toBe("Bearer jwt-token");
  });

  it("streamUrl은 SSE_PUBLIC_ORIGIN이 없으면 API_ORIGIN으로 만든다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(ticketBody(), 201)),
    );

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    const body = (await response.json()) as { data: { streamUrl: string } };
    expect(body.data.streamUrl).toBe(
      "http://api.internal:8080/api/v1/notifications/stream",
    );
  });

  it("streamUrl은 SSE_PUBLIC_ORIGIN이 있으면 그 주소로 만든다", async () => {
    env.SSE_PUBLIC_ORIGIN = "https://api.ogu.example";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(ticketBody(), 201)),
    );

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    const body = (await response.json()) as { data: { streamUrl: string } };
    expect(body.data.streamUrl).toBe(
      "https://api.ogu.example/api/v1/notifications/stream",
    );
  });

  it("SSE_PUBLIC_ORIGIN 끝에 슬래시가 있어도 주소가 겹치지 않는다", async () => {
    env.SSE_PUBLIC_ORIGIN = "https://api.ogu.example/";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(ticketBody(), 201)),
    );

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    const body = (await response.json()) as { data: { streamUrl: string } };
    expect(body.data.streamUrl).toBe(
      "https://api.ogu.example/api/v1/notifications/stream",
    );
  });

  it("API가 세션 오류 401이면 callWithSessionRefresh로 한 번 갱신하고 다시 받는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(jsonResponse(ticketBody("ticket-new"), 201));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      ticketRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
    );

    expect(response.status).toBe(200);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      API_TICKET_URL,
      API_REFRESH_URL,
      API_TICKET_URL,
    ]);
    expect(authorizationOf(fetchMock.mock.calls[2])).toBe("Bearer new-access");
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe("new-access");
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "new-refresh",
    );
    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
    const body = (await response.json()) as { data: { ticket: string } };
    expect(body.data.ticket).toBe("ticket-new");
  });

  it("응답 본문에는 access, refresh 토큰이 실리지 않는다(FR-012)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(
        jsonResponse(
          {
            success: true,
            data: {
              ticket: "ticket-new",
              expiresAt: "2026-10-06T00:00:30Z",
              accessToken: "leaked-access",
            },
            error: null,
          },
          201,
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      ticketRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
    );

    const text = await response.text();
    expect(text).not.toContain("new-access");
    expect(text).not.toContain("new-refresh");
    expect(text).not.toContain("leaked-access");
    expect(Object.keys(JSON.parse(text).data).sort()).toEqual([
      "expiresAt",
      "streamUrl",
      "ticket",
    ]);
  });

  it("US1-AC8 로그인하지 않으면(쿠키 없음) 401이고 티켓이 없다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(errorBody("UNAUTHORIZED"), 401));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(ticketRequest());

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      data: null,
      error: { code: "UNAUTHORIZED" },
    });
    expect(
      fetchMock.mock.calls.filter(([url]) => url === API_REFRESH_URL),
    ).toHaveLength(0);
  });

  it("US1-AC8 갱신도 거절되면 세 쿠키를 지우고 401 SESSION_EXPIRED다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401))
      .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      ticketRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
        [ONBOARDED_COOKIE]: "1",
      }),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "SESSION_EXPIRED" },
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe("");
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe("");
  });

  it("온보딩 전 회원의 403은 그대로 전달한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(jsonResponse(errorBody("ONBOARDING_REQUIRED"), 403)),
    );

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "ONBOARDING_REQUIRED" },
    });
  });

  it("API가 2xx인데 티켓이 없으면 502로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );

    const response = await POST(
      ticketRequest({ [ACCESS_TOKEN_COOKIE]: "jwt-token" }),
    );

    expect(response.status).toBe(502);
  });

  it("Origin이 서비스 출처와 다르면 403이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(
      ticketRequest(
        { [ACCESS_TOKEN_COOKIE]: "jwt-token" },
        "https://evil.example",
      ),
    );

    expect(response.status).toBe(403);
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "FORBIDDEN_ORIGIN" },
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
