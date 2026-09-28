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

  it("네트워크 실패는 502와 ApiResponse 오류 봉투로 바꾼다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("fetch failed")),
    );

    const result = await callApi("/api/v1/members/me", "203.0.113.1");

    expect(result.status).toBe(502);
    expect(result.body.success).toBe(false);
    expect(result.body.data).toBeNull();
    expect(result.body.error?.code).toEqual(expect.any(String));
  });
});
