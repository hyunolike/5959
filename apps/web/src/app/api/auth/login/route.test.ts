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

import {
  ACCESS_TOKEN_COOKIE,
  ONBOARDED_COOKIE,
  REFRESH_TOKEN_COOKIE,
} from "@/shared/server";

import { POST } from "./route";

const jsonResponse = (
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", ...headers },
  });

function authResult(onboarded: boolean) {
  return {
    member: {
      id: 1,
      authMethod: "EMAIL",
      email: "user@example.com",
      nickname: onboarded ? "오구" : null,
      jobRole: onboarded ? "DEVELOPMENT" : null,
      careerYear: onboarded ? "YEAR_1" : null,
      onboarded,
    },
    tokens: {
      accessToken: "access-token",
      accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
    },
    newMember: false,
  };
}

function loginRequest(body: unknown): NextRequest {
  return new NextRequest("http://localhost:3000/api/auth/login", {
    method: "POST",
    headers: {
      origin: "http://localhost:3000",
      "content-type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("POST /api/auth/login", () => {
  it("Origin이 다르면 403 FORBIDDEN_ORIGIN이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/auth/login", {
      method: "POST",
      headers: {
        origin: "http://evil.example",
        "content-type": "application/json",
      },
      body: JSON.stringify({ email: "a@b.com", password: "abcd1234" }),
    });
    const response = await POST(request);

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US2-AC1 로그인에 성공하면 200과 member만 담긴 본문을 돌려주고 세션 쿠키를 심는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: authResult(true), error: null },
            200,
          ),
        ),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({
      success: true,
      data: { member: authResult(true).member },
      error: null,
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "access-token",
    );
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "refresh-token",
    );
  });

  it("로그인 응답 본문 어디에도 accessToken, refreshToken 키가 없다(FR-012)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: authResult(true), error: null },
            200,
          ),
        ),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );
    const text = await response.text();

    expect(text).not.toContain("accessToken");
    expect(text).not.toContain("refreshToken");
  });

  it("온보딩을 마친 회원이 로그인하면 ogu_ob 쿠키를 설정한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: authResult(true), error: null },
            200,
          ),
        ),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
  });

  it("온보딩 전 회원이 로그인하면 ogu_ob 쿠키를 지운다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: authResult(false), error: null },
            200,
          ),
        ),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("");
    expect(response.cookies.get(ONBOARDED_COOKIE)?.maxAge).toBe(0);
  });

  it("US2-AC2 비밀번호가 틀리거나 이메일이 없으면 401 오류 봉투를 그대로 전달하고 쿠키를 설정하지 않는다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: {
        code: "INVALID_CREDENTIALS",
        message: "이메일 또는 비밀번호가 올바르지 않습니다.",
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(errorBody, 401)),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "wrongpass" }),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toEqual(errorBody);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("US2-AC3 429면 상태와 오류 봉투, Retry-After 헤더를 그대로 전달한다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: {
        code: "LOGIN_THROTTLED",
        message: "로그인 시도가 너무 많습니다. 잠시 후 다시 시도해 주세요.",
        retryAfterSeconds: 900,
      },
    };
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(errorBody, 429, { "retry-after": "900" }),
        ),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    expect(response.status).toBe(429);
    expect(response.headers.get("Retry-After")).toBe("900");
    await expect(response.json()).resolves.toEqual(errorBody);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("업스트림이 2xx인데 본문이 비어 있으면 502 오류 봉투로 바꾼다(예상 밖의 응답)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("", { status: 200 })),
    );

    const response = await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({
      success: false,
      data: null,
      error: { code: "INTERNAL_ERROR", message: expect.any(String) },
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("apps/api로 /api/v1/auth/login을 부르고 요청 본문을 그대로 전달한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse(
          { success: true, data: authResult(true), error: null },
          200,
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    await POST(
      loginRequest({ email: "user@example.com", password: "abcd1234" }),
    );

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/auth/login");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(
      JSON.stringify({ email: "user@example.com", password: "abcd1234" }),
    );
  });
});
