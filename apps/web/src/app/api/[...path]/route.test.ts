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

import { ACCESS_TOKEN_COOKIE } from "@/shared/server";

import { DELETE, GET, PATCH, POST, PUT } from "./route";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

afterEach(() => {
  vi.unstubAllGlobals();
});

function params(path: string[]) {
  return { params: Promise.resolve({ path }) };
}

describe("GET /api/[...path]", () => {
  it("API_ORIGIN/api/v1/{path}로 전달하고 응답을 그대로 돌려준다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: { id: 1 }, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/members/me");
    const response = await GET(request, params(["members", "me"]));

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      success: true,
      data: { id: 1 },
      error: null,
    });
    const [url] = fetchMock.mock.calls[0] as [string];
    expect(url).toBe("http://api.internal:8080/api/v1/members/me");
  });

  it("__Host-ogu_at 쿠키를 Authorization: Bearer로 바꿔 보낸다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/members/me", {
      headers: { cookie: `${ACCESS_TOKEN_COOKIE}=jwt-token` },
    });
    await GET(request, params(["members", "me"]));

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer jwt-token");
  });

  it("access 토큰 쿠키가 없으면 Authorization 헤더를 붙이지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/members/me");
    await GET(request, params(["members", "me"]));

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Headers;
    expect(headers.has("Authorization")).toBe(false);
  });

  it("쿼리 문자열을 그대로 전달한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest(
      "http://localhost:3000/api/members/nickname-availability?nickname=%EC%98%A4%EA%B5%AC",
    );
    await GET(request, params(["members", "nickname-availability"]));

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(new URL(url).searchParams.get("nickname")).toBe("오구");
    expect(new URL(url).pathname).toBe("/api/v1/members/nickname-availability");
  });

  it("GET은 Origin이 없어도(또는 달라도) origin-guard를 적용하지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/members/me", {
      headers: { origin: "http://evil.example" },
    });
    const response = await GET(request, params(["members", "me"]));

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledOnce();
  });
});

describe("상태 변경 메서드의 origin-guard", () => {
  it.each([
    ["POST", POST],
    ["PUT", PUT],
    ["PATCH", PATCH],
    ["DELETE", DELETE],
  ] as const)(
    "%s는 Origin이 APP_ORIGIN과 다르면 403 FORBIDDEN_ORIGIN을 돌려주고 API를 부르지 않는다",
    async (method, handler) => {
      const fetchMock = vi.fn();
      vi.stubGlobal("fetch", fetchMock);

      const request = new NextRequest(
        "http://localhost:3000/api/members/me/onboarding",
        { method, headers: { origin: "http://evil.example" } },
      );
      const response = await handler(
        request,
        params(["members", "me", "onboarding"]),
      );

      expect(response.status).toBe(403);
      await expect(response.json()).resolves.toEqual({
        success: false,
        data: null,
        error: { code: "FORBIDDEN_ORIGIN", message: expect.any(String) },
      });
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("Origin이 같으면 요청 본문을 그대로 전달한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const requestBody = JSON.stringify({ email: "a@a.com", password: "pw" });
    const request = new NextRequest("http://localhost:3000/api/auth/login", {
      method: "POST",
      headers: {
        origin: "http://localhost:3000",
        "content-type": "application/json",
      },
      body: requestBody,
    });
    await POST(request, params(["auth", "login"]));

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBe(requestBody);
    const headers = init.headers as Headers;
    expect(headers.get("content-type")).toBe("application/json");
  });
});
