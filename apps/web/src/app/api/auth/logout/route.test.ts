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

function logoutRequest({
  withAccessToken = true,
}: { withAccessToken?: boolean } = {}): NextRequest {
  const headers: Record<string, string> = { origin: "http://localhost:3000" };
  if (withAccessToken) {
    headers.cookie = `${ACCESS_TOKEN_COOKIE}=old-access-token`;
  }
  return new NextRequest("http://localhost:3000/api/auth/logout", {
    method: "POST",
    headers,
  });
}

function assertAllCookiesCleared(response: NextResponse) {
  for (const name of [
    ACCESS_TOKEN_COOKIE,
    REFRESH_TOKEN_COOKIE,
    ONBOARDED_COOKIE,
  ]) {
    expect(response.cookies.get(name)?.value).toBe("");
    expect(response.cookies.get(name)?.maxAge).toBe(0);
  }
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("POST /api/auth/logout", () => {
  it("Origin이 다르면 403 FORBIDDEN_ORIGIN이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/auth/logout", {
      method: "POST",
      headers: {
        origin: "http://evil.example",
        cookie: `${ACCESS_TOKEN_COOKIE}=old-access-token`,
      },
    });
    const response = await POST(request);

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US2-AC5 로그아웃하면 204이고 세 쿠키를 모두 지운다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(logoutRequest());

    expect(response.status).toBe(204);
    await expect(response.text()).resolves.toBe("");
    assertAllCookiesCleared(response);
  });

  it("ogu_at 쿠키를 Authorization: Bearer로 바꿔 /api/v1/auth/logout을 부른다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await POST(logoutRequest());

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/auth/logout");
    expect(init.method).toBe("POST");
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer old-access-token");
  });

  it("이미 무효인 세션이라 API가 401을 돌려줘도 204이고 쿠키를 지운다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: { code: "SESSION_EXPIRED", message: "세션이 만료되었습니다." },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(errorBody), {
          status: 401,
          headers: { "content-type": "application/json" },
        }),
      ),
    );

    const response = await POST(logoutRequest());

    expect(response.status).toBe(204);
    assertAllCookiesCleared(response);
  });

  it("API 호출이 네트워크 오류여도 204이고 쿠키를 지운다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed")),
    );

    const response = await POST(logoutRequest());

    expect(response.status).toBe(204);
    assertAllCookiesCleared(response);
  });

  it("access 토큰 쿠키가 없어도 204이고 쿠키를 지운다(API를 부르지 않는다)", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const response = await POST(logoutRequest({ withAccessToken: false }));

    expect(response.status).toBe(204);
    expect(fetchMock).not.toHaveBeenCalled();
    assertAllCookiesCleared(response);
  });
});
