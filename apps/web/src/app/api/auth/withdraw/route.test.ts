// @vitest-environment node
import { NextRequest, type NextResponse } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/shared/config", () => ({
  env: {
    API_ORIGIN: "http://api.internal:8080",
    BFF_API_KEY: "test-bff-key",
    APP_ORIGIN: "http://localhost:3000",
  },
}));

import {
  ACCESS_TOKEN_COOKIE,
  ONBOARDED_COOKIE,
  REFRESH_TOKEN_COOKIE,
} from "@/shared/server";

import { POST } from "./route";

const SESSION_COOKIES = [
  ACCESS_TOKEN_COOKIE,
  REFRESH_TOKEN_COOKIE,
  ONBOARDED_COOKIE,
];

function withdrawRequest(origin = "http://localhost:3000"): NextRequest {
  return new NextRequest("http://localhost:3000/api/auth/withdraw", {
    method: "POST",
    headers: {
      origin,
      "content-type": "application/json",
      cookie: `${ACCESS_TOKEN_COOKIE}=access-token; ${REFRESH_TOKEN_COOKIE}=refresh-token`,
    },
    body: JSON.stringify({ confirmed: true }),
  });
}

function errorResponse(status: number, code: string): Response {
  return new Response(
    JSON.stringify({
      success: false,
      data: null,
      error: { code, message: "실패" },
    }),
    { status, headers: { "content-type": "application/json" } },
  );
}

function assertCookiesKept(response: NextResponse) {
  for (const name of SESSION_COOKIES) {
    expect(response.cookies.get(name)).toBeUndefined();
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("POST /api/auth/withdraw", () => {
  it("Origin이 다르면 403이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(withdrawRequest("http://evil.example"));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US1-AC4 탈퇴하면 204이고 세 쿠키를 모두 지운다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(withdrawRequest());

    expect(response.status).toBe(204);
    for (const name of SESSION_COOKIES) {
      expect(response.cookies.get(name)?.value).toBe("");
      expect(response.cookies.get(name)?.maxAge).toBe(0);
    }
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/members/me/withdrawal");
    expect(init.method).toBe("POST");
    expect((init.headers as Headers).get("Authorization")).toBe(
      "Bearer access-token",
    );
    expect(init.body).toBe(JSON.stringify({ confirmed: true }));
  });

  it("US1-AC8 API가 실패하면 오류를 그대로 돌려주고 쿠키를 지우지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(errorResponse(500, "INTERNAL_ERROR")),
    );

    const response = await POST(withdrawRequest());

    expect(response.status).toBe(500);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: { code: "INTERNAL_ERROR" },
    });
    assertCookiesKept(response);
  });

  it("US1-AC11 운영자의 409를 그대로 돌려주고 쿠키를 지우지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(errorResponse(409, "OPERATOR_CANNOT_WITHDRAW")),
    );

    const response = await POST(withdrawRequest());

    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "OPERATOR_CANNOT_WITHDRAW" },
    });
    assertCookiesKept(response);
  });

  it("2xx인데 204가 아니면 성공으로 보이지 않게 502로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 200 })),
    );

    const response = await POST(withdrawRequest());

    expect(response.status).toBe(502);
    assertCookiesKept(response);
  });
});
