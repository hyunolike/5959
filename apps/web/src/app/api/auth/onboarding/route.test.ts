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

import { PUT } from "./route";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const ONBOARDING_RESULT = {
  member: {
    id: 1,
    authMethod: "EMAIL",
    email: "new@example.com",
    nickname: "오구",
    jobRole: "DEVELOPMENT",
    careerYear: "YEAR_1",
    onboarded: true,
  },
  accessToken: "new-access-token",
  accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
};

function onboardingRequest(
  body: unknown,
  { withAccessToken = true }: { withAccessToken?: boolean } = {},
): NextRequest {
  const headers: Record<string, string> = {
    origin: "http://localhost:3000",
    "content-type": "application/json",
  };
  if (withAccessToken) {
    headers.cookie = `${ACCESS_TOKEN_COOKIE}=old-access-token`;
  }
  return new NextRequest("http://localhost:3000/api/auth/onboarding", {
    method: "PUT",
    headers,
    body: JSON.stringify(body),
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("PUT /api/auth/onboarding", () => {
  it("Origin이 다르면 403 FORBIDDEN_ORIGIN이고 API를 부르지 않는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest(
      "http://localhost:3000/api/auth/onboarding",
      {
        method: "PUT",
        headers: {
          origin: "http://evil.example",
          "content-type": "application/json",
        },
        body: JSON.stringify({
          nickname: "오구",
          jobRole: "DEVELOPMENT",
          careerYear: "YEAR_1",
        }),
      },
    );
    const response = await PUT(request);

    expect(response.status).toBe(403);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US1-AC4 온보딩을 마치면 200과 member만 담긴 본문을 돌려주고 ogu_at을 교체, ogu_ob를 설정한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: ONBOARDING_RESULT, error: null },
            200,
          ),
        ),
    );

    const response = await PUT(
      onboardingRequest({
        nickname: "오구",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );

    expect(response.status).toBe(200);
    const body = await response.json();
    expect(body).toEqual({
      success: true,
      data: { member: ONBOARDING_RESULT.member },
      error: null,
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "new-access-token",
    );
    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
    // OnboardingResult에는 refresh 토큰이 없다 — ogu_rt는 건드리지 않는다.
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)).toBeUndefined();
  });

  it("온보딩 응답 본문 어디에도 accessToken 키가 없다(FR-012)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(
            { success: true, data: ONBOARDING_RESULT, error: null },
            200,
          ),
        ),
    );

    const response = await PUT(
      onboardingRequest({
        nickname: "오구",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );
    const text = await response.text();

    expect(text).not.toContain("accessToken");
  });

  it("ogu_at 쿠키를 Authorization: Bearer로 바꿔 보낸다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse(
          { success: true, data: ONBOARDING_RESULT, error: null },
          200,
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    await PUT(
      onboardingRequest({
        nickname: "오구",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/members/me/onboarding");
    const headers = init.headers as Headers;
    expect(headers.get("Authorization")).toBe("Bearer old-access-token");
  });

  it("US1-AC5 닉네임이 중복이면 409 오류 봉투를 그대로 전달하고 쿠키를 바꾸지 않는다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: { code: "NICKNAME_TAKEN", message: "이미 쓰이는 닉네임입니다." },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(errorBody, 409)),
    );

    const response = await PUT(
      onboardingRequest({
        nickname: "오구",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );

    expect(response.status).toBe(409);
    await expect(response.json()).resolves.toEqual(errorBody);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
    expect(response.cookies.get(ONBOARDED_COOKIE)).toBeUndefined();
  });

  it("US1-AC6 닉네임 형식이 잘못되면 400 오류 봉투를 그대로 전달한다", async () => {
    const errorBody = {
      success: false,
      data: null,
      error: { code: "INVALID_REQUEST", message: "허용되지 않은 문자입니다." },
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(errorBody, 400)),
    );

    const response = await PUT(
      onboardingRequest({
        nickname: "오 구!",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toEqual(errorBody);
  });

  it("업스트림이 2xx인데 본문이 비어 있으면 502 오류 봉투로 바꾼다(예상 밖의 응답)", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response("", { status: 200 })),
    );

    const response = await PUT(
      onboardingRequest({
        nickname: "오구",
        jobRole: "DEVELOPMENT",
        careerYear: "YEAR_1",
      }),
    );

    expect(response.status).toBe(502);
    await expect(response.json()).resolves.toEqual({
      success: false,
      data: null,
      error: { code: "INTERNAL_ERROR", message: expect.any(String) },
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("access 토큰 쿠키도 refresh 토큰 쿠키도 없으면 Authorization 헤더 없이 호출하고 API의 401을 그대로 전달한다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "UNAUTHORIZED", message: "인증이 필요합니다." },
        },
        401,
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const response = await PUT(
      onboardingRequest(
        { nickname: "오구", jobRole: "DEVELOPMENT", careerYear: "YEAR_1" },
        { withAccessToken: false },
      ),
    );

    expect(response.status).toBe(401);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const headers = init.headers as Headers;
    expect(headers.has("Authorization")).toBe(false);
  });
});

const API_REFRESH_URL = "http://api.internal:8080/api/v1/auth/refresh";
const API_ONBOARDING_URL =
  "http://api.internal:8080/api/v1/members/me/onboarding";
const ONBOARDING_BODY = {
  nickname: "오구",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_1",
};

function refreshedBody(refreshToken: string | null = "new-refresh") {
  return {
    success: true,
    data: {
      member: { ...ONBOARDING_RESULT.member, onboarded: false },
      tokens: {
        accessToken: "refreshed-access",
        accessTokenExpiresAt: new Date(Date.now() + 900_000).toISOString(),
        refreshToken,
        refreshTokenExpiresAt: new Date(
          Date.now() + 14 * 86_400_000,
        ).toISOString(),
      },
      newMember: false,
    },
    error: null,
  };
}

const errorBody = (code: string) => ({
  success: false,
  data: null,
  error: { code, message: "실패" },
});

function sessionRequest(cookies: Record<string, string>): NextRequest {
  return new NextRequest("http://localhost:3000/api/auth/onboarding", {
    method: "PUT",
    headers: {
      origin: "http://localhost:3000",
      "content-type": "application/json",
      cookie: Object.entries(cookies)
        .map(([name, value]) => `${name}=${value}`)
        .join("; "),
    },
    body: JSON.stringify(ONBOARDING_BODY),
  });
}

function authorizationOf(call: unknown[]): string | null {
  return ((call[1] as RequestInit).headers as Headers).get("Authorization");
}

describe("PUT /api/auth/onboarding 세션 갱신(US4-AC1)", () => {
  it("ogu_at이 없고 ogu_rt만 있으면 먼저 refresh한 뒤 새 access 토큰으로 온보딩한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: ONBOARDING_RESULT, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await PUT(
      sessionRequest({ [REFRESH_TOKEN_COOKIE]: "old-refresh" }),
    );

    expect(response.status).toBe(200);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      API_REFRESH_URL,
      API_ONBOARDING_URL,
    ]);
    expect(authorizationOf(fetchMock.mock.calls[1])).toBe(
      "Bearer refreshed-access",
    );
    // 교체된 refresh 토큰은 저장하고, access는 온보딩 결과의 새 토큰으로 덮는다.
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "new-refresh",
    );
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "new-access-token",
    );
    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
  });

  it("API가 401 UNAUTHORIZED면 한 번 refresh하고 한 번만 다시 보낸다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody(null)))
      .mockResolvedValueOnce(
        jsonResponse({ success: true, data: ONBOARDING_RESULT, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await PUT(
      sessionRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
    );

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const [, retried] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect(retried.method).toBe("PUT");
    expect(JSON.parse(retried.body as string)).toEqual(ONBOARDING_BODY);
    // 유예 구간(refreshToken null)이면 ogu_rt는 건드리지 않는다.
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)).toBeUndefined();
  });

  it("다시 보낸 요청이 409여도 교체된 refresh 토큰은 쿠키에 저장한다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(errorBody("SESSION_EXPIRED"), 401))
        .mockResolvedValueOnce(jsonResponse(refreshedBody()))
        .mockResolvedValueOnce(jsonResponse(errorBody("NICKNAME_TAKEN"), 409)),
    );

    const response = await PUT(
      sessionRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
    );

    expect(response.status).toBe(409);
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "new-refresh",
    );
  });

  it("refresh가 거절되면(4xx) 세 쿠키를 지우고 401을 돌려준다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(errorBody("SESSION_EXPIRED"), 401))
      .mockResolvedValueOnce(jsonResponse(errorBody("SESSION_EXPIRED"), 401));
    vi.stubGlobal("fetch", fetchMock);

    const response = await PUT(
      sessionRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
        [ONBOARDED_COOKIE]: "1",
      }),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      error: { code: "SESSION_EXPIRED" },
    });
    for (const name of [
      ACCESS_TOKEN_COOKIE,
      REFRESH_TOKEN_COOKIE,
      ONBOARDED_COOKIE,
    ]) {
      expect(response.cookies.get(name)?.maxAge).toBe(0);
    }
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("refresh가 API 장애(5xx)로 실패하면 쿠키를 지우지 않고 그 오류를 돌려준다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(errorBody("UNAUTHORIZED"), 401))
        .mockResolvedValueOnce(jsonResponse(errorBody("INTERNAL_ERROR"), 500)),
    );

    const response = await PUT(
      sessionRequest({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
    );

    expect(response.status).toBe(500);
    expect(response.headers.getSetCookie()).toEqual([]);
  });
});
