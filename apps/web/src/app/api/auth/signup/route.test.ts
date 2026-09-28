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

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const AUTH_RESULT = {
  member: {
    id: 1,
    authMethod: "EMAIL",
    email: "new@example.com",
    nickname: null,
    jobRole: null,
    careerYear: null,
    onboarded: false,
  },
  tokens: {
    accessToken: "access-token",
    accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
    refreshToken: "refresh-token",
    refreshTokenExpiresAt: new Date(Date.now() + 3_600_000).toISOString(),
  },
  newMember: true,
};

function signupRequest(body: unknown): NextRequest {
  return new NextRequest("http://localhost:3000/api/auth/signup", {
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

describe("POST /api/auth/signup", () => {
  it("Origin이 다르면 403 FORBIDDEN_ORIGIN이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/auth/signup", {
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

  it("US1-AC1 가입에 성공하면 201과 member만 담긴 본문을 돌려주고 세션 쿠키를 심는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ success: true, data: AUTH_RESULT, error: null }, 201),
        ),
    );

    const response = await POST(
      signupRequest({ email: "new@example.com", password: "abcd1234" }),
    );

    expect(response.status).toBe(201);
    const body = await response.json();
    expect(body).toEqual({
      success: true,
      data: { member: AUTH_RESULT.member },
      error: null,
    });

    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "access-token",
    );
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "refresh-token",
    );
  });

  it("가입 응답 본문 어디에도 accessToken, refreshToken 키가 없다(FR-012)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ success: true, data: AUTH_RESULT, error: null }, 201),
        ),
    );

    const response = await POST(
      signupRequest({ email: "new@example.com", password: "abcd1234" }),
    );
    const text = await response.text();

    expect(text).not.toContain("accessToken");
    expect(text).not.toContain("refreshToken");
  });

  it("가입 성공 시 ogu_ob 쿠키를 지운다(새 회원은 아직 온보딩 전이다)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse({ success: true, data: AUTH_RESULT, error: null }, 201),
        ),
    );

    const response = await POST(
      signupRequest({ email: "new@example.com", password: "abcd1234" }),
    );

    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("");
    expect(response.cookies.get(ONBOARDED_COOKIE)?.maxAge).toBe(0);
  });

  it("US1-AC2 이미 가입된 이메일이면 409 오류 봉투를 그대로 전달하고 쿠키를 설정하지 않는다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: {
        code: "EMAIL_ALREADY_REGISTERED",
        message: "이미 가입된 이메일입니다.",
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(errorBody, 409)),
    );

    const response = await POST(
      signupRequest({ email: "dup@example.com", password: "abcd1234" }),
    );

    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toEqual(errorBody);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("US1-AC3 비밀번호 규칙 위반이면 400 오류 봉투를 그대로 전달한다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: {
        code: "INVALID_REQUEST",
        message: "비밀번호 규칙을 확인하세요.",
      },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(errorBody, 400)),
    );

    const response = await POST(
      signupRequest({ email: "new@example.com", password: "short" }),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual(errorBody);
  });

  it("apps/api로 /api/v1/auth/signup을 부르고 요청 본문을 그대로 전달한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: AUTH_RESULT, error: null }, 201),
      );
    vi.stubGlobal("fetch", fetchMock);

    await POST(
      signupRequest({ email: "new@example.com", password: "abcd1234" }),
    );

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/auth/signup");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(
      JSON.stringify({ email: "new@example.com", password: "abcd1234" }),
    );
  });
});
