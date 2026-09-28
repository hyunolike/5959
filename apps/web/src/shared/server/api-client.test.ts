// @vitest-environment node
import { afterEach, describe, expect, it, vi } from "vitest";

import { callApi } from "./api-client";

vi.mock("@/shared/config", () => ({
  env: {
    API_ORIGIN: "http://api.internal:8080/",
    BFF_API_KEY: "test-bff-key",
  },
}));

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("callApi", () => {
  it("API_ORIGIN과 경로를 합쳐서 요청하고, X-Ogu-Bff-Key와 X-Ogu-Client-Ip를 붙인다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: {}, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await callApi("/api/v1/members/me", "203.0.113.1");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/members/me");
    const headers = init.headers as Headers;
    expect(headers.get("X-Ogu-Bff-Key")).toBe("test-bff-key");
    expect(headers.get("X-Ogu-Client-Ip")).toBe("203.0.113.1");
  });

  it("호출자가 준 헤더(Authorization 등)를 그대로 함께 보낸다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: {}, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await callApi("/api/v1/members/me", "203.0.113.1", {
      headers: { Authorization: "Bearer token-123" },
    });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer token-123");
    expect(headers.get("X-Ogu-Bff-Key")).toBe("test-bff-key");
  });

  it("15초 타임아웃 신호를 fetch에 붙인다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: {}, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await callApi("/api/v1/members/me", "203.0.113.1");

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("성공 응답을 status와 ApiResponse 그대로 돌려준다", async () => {
    const payload = { success: true, data: { id: 1 }, error: null };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(payload, 200)),
    );

    const result = await callApi<{ id: number }>(
      "/api/v1/members/me",
      "203.0.113.1",
    );

    expect(result).toEqual({ status: 200, body: payload });
  });

  it("오류 응답도 status와 ApiResponse 오류 봉투 그대로 돌려준다", async () => {
    const payload = {
      success: false,
      data: null,
      error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(payload, 401)),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result).toEqual({ status: 401, body: payload });
  });

  it("204는 상태만 그대로 돌려주고 본문은 null이다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );

    const result = await callApi("/api/v1/auth/logout", "203.0.113.1");

    expect(result).toEqual({ status: 204, body: null });
  });

  it("본문이 빈 문자열이면 본문은 null이다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("", { status: 200 })),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result).toEqual({ status: 200, body: null });
  });

  it("JSON이 아닌 5xx 본문은 원래 상태를 유지한 채 INTERNAL_ERROR 봉투로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("<html>Internal Server Error</html>", { status: 500 }),
        ),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result).toEqual({
      status: 500,
      body: {
        success: false,
        data: null,
        error: { code: "INTERNAL_ERROR", message: expect.any(String) },
      },
    });
  });

  it("JSON이 아닌 4xx 본문은 원래 상태를 유지한 채 INVALID_REQUEST 봉투로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("bad request", { status: 400 })),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result).toEqual({
      status: 400,
      body: {
        success: false,
        data: null,
        error: { code: "INVALID_REQUEST", message: expect.any(String) },
      },
    });
  });

  it("네트워크 실패는 502와 ApiResponse 오류 봉투로 바꾸고 콘솔에 남긴다(시크릿 없이)", async () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed")),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1", {
      headers: { Authorization: "Bearer super-secret-token" },
    });

    expect(result.status).toBe(502);
    expect(result.body).toEqual({
      success: false,
      data: null,
      error: { code: "API_UNAVAILABLE", message: expect.any(String) },
    });
    expect(consoleError).toHaveBeenCalledTimes(1);
    const loggedArgs = consoleError.mock.calls[0]?.map(String).join(" ");
    expect(loggedArgs).not.toContain("super-secret-token");
    expect(loggedArgs).not.toContain("test-bff-key");
  });

  it("redirect: manual을 fetch에 붙여 업스트림 리다이렉트를 따라가지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: {}, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await callApi("/api/v1/members/me", "203.0.113.1");

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.redirect).toBe("manual");
  });

  it("업스트림이 3xx를 돌려주면 API_UNAVAILABLE 오류 봉투로 바꾸고 리다이렉트를 따라가지 않는다(X-Ogu-Bff-Key를 두 번째 요청에 싣지 않는다)", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(null, {
        status: 302,
        headers: { location: "http://evil.example/steal" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result).toEqual({
      status: 502,
      body: {
        success: false,
        data: null,
        error: { code: "API_UNAVAILABLE", message: expect.any(String) },
      },
    });
    // 두 번째 요청(리다이렉트를 따라간 요청)이 없어야 한다 — X-Ogu-Bff-Key 유출 방지.
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("15초 안에 응답이 없으면(타임아웃) 504와 ApiResponse 오류 봉투로 바꾼다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockRejectedValue(
          new DOMException("The operation timed out.", "TimeoutError"),
        ),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result.status).toBe(504);
    expect(result.body).toEqual({
      success: false,
      data: null,
      error: { code: "API_UNAVAILABLE", message: expect.any(String) },
    });
  });
});
