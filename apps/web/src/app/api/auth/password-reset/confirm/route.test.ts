// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("@/shared/config", () => ({
  env: {
    API_ORIGIN: "http://api.internal:8080",
    BFF_API_KEY: "test-bff-key",
    APP_ORIGIN: "http://localhost:3000",
  },
}));

import { POST } from "./route";

function bffRequest(origin = "http://localhost:3000"): NextRequest {
  return new NextRequest(
    "http://localhost:3000/api/auth/password-reset/confirm",
    {
      method: "POST",
      headers: {
        origin,
        "content-type": "application/json",
        "x-forwarded-for": "203.0.113.7",
      },
      body: JSON.stringify({ any: "body" }),
    },
  );
}

function errorResponse(status: number, code: string, headers?: HeadersInit) {
  return new Response(
    JSON.stringify({
      success: false,
      data: null,
      error: { code, message: "실패" },
    }),
    { status, headers: { "content-type": "application/json", ...headers } },
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("POST /api/auth/password-reset/confirm", () => {
  it("Origin이 다르면 403이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(bffRequest("http://evil.example"));

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("본문과 클라이언트 IP를 넘기고 204를 그대로 돌려준다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(bffRequest());

    expect(response.status).toBe(204);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe(
      "http://api.internal:8080/api/v1/auth/password-reset/confirm",
    );
    expect(init.body).toBe(JSON.stringify({ any: "body" }));
    expect((init.headers as Headers).get("X-Ogu-Client-Ip")).toBe(
      "203.0.113.7",
    );
    // 토큰을 다루지 않으므로 쿠키를 건드리지 않는다
    expect(response.headers.get("set-cookie")).toBeNull();
  });

  it("오류 봉투와 Retry-After를 그대로 돌려준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        errorResponse(429, "PASSWORD_RESET_THROTTLED", {
          "Retry-After": "120",
        }),
      ),
    );

    const response = await POST(bffRequest());

    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("120");
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "PASSWORD_RESET_THROTTLED" },
    });
  });

  it("2xx인데 204가 아니면 502로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 200 })),
    );

    const response = await POST(bffRequest());

    expect(response.status).toBe(502);
  });
});
