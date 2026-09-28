// @vitest-environment node
import { NextRequest } from "next/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mockEnv = vi.hoisted(() => ({
  API_ORIGIN: "http://api.internal:8080",
  BFF_API_KEY: "test-bff-key",
  APP_ORIGIN: "https://ogu.example",
  APP_ENV: "development",
  OAUTH_STATE_SECRET: "test-oauth-state-secret-0123456789abcdef",
}));

vi.mock("@/shared/config", () => ({ env: mockEnv }));

import {
  ACCESS_TOKEN_COOKIE,
  ONBOARDED_COOKIE,
  OAUTH_STATE_COOKIE,
  REFRESH_TOKEN_COOKIE,
  serializeOAuthState,
  type OAuthStatePayload,
} from "@/shared/server";

import { GET } from "./route";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function authResult(onboarded: boolean) {
  return {
    member: {
      id: 7,
      authMethod: "KAKAO",
      email: null,
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
    newMember: !onboarded,
  };
}

function statePayload(
  overrides: Partial<OAuthStatePayload> = {},
): OAuthStatePayload {
  return {
    provider: "kakao",
    state: "good-state",
    codeVerifier: null,
    next: "/home",
    issuedAt: Date.now(),
    ...overrides,
  };
}

function callback(
  provider: string,
  query: string,
  cookiePayload: OAuthStatePayload | null = statePayload(),
) {
  const headers = new Headers();
  if (cookiePayload !== null) {
    headers.set(
      "cookie",
      `${OAUTH_STATE_COOKIE}=${serializeOAuthState(cookiePayload, mockEnv.OAUTH_STATE_SECRET)}`,
    );
  }
  const request = new NextRequest(
    `https://ogu.example/api/auth/oauth/${provider}/callback${query}`,
    { headers },
  );
  return GET(request, { params: Promise.resolve({ provider }) });
}

function expectRedirect(response: Response, to: string) {
  expect(response.status).toBe(302);
  expect(response.headers.get("location")).toBe(`https://ogu.example${to}`);
}

function expectStateCookieCleared(response: {
  cookies: {
    get(name: string): { value: string; maxAge?: number } | undefined;
  };
}) {
  const cookie = response.cookies.get(OAUTH_STATE_COOKIE);
  expect(cookie?.value).toBe("");
  expect(cookie?.maxAge).toBe(0);
}

let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
  fetchMock = vi.fn();
  vi.stubGlobal("fetch", fetchMock);
  vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("GET /api/auth/oauth/{provider}/callback", () => {
  it("US3-AC1 처음 쓰는 계정이면 세션 쿠키를 심고 ogu_ob를 지운 뒤 /onboarding으로 보낸다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(false), error: null }),
    );

    const response = await callback(
      "kakao",
      "?code=provider-code&state=good-state",
      statePayload({ next: "/my" }),
    );

    expectRedirect(response, "/onboarding");
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "access-token",
    );
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "refresh-token",
    );
    expect(response.cookies.get(ONBOARDED_COOKIE)?.maxAge).toBe(0);
    expectStateCookieCleared(response);
  });

  it("카카오: API에 code와 redirectUri만 보내고 codeVerifier는 보내지 않는다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(false), error: null }),
    );

    await callback("kakao", "?code=provider-code&state=good-state");

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/auth/oauth/kakao");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      code: "provider-code",
      redirectUri: "https://ogu.example/api/auth/oauth/kakao/callback",
    });
    expect(new Headers(init.headers).get("X-Ogu-Bff-Key")).toBe("test-bff-key");
  });

  it("구글: 쿠키의 code_verifier를 함께 보낸다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(false), error: null }),
    );

    await callback(
      "google",
      "?code=provider-code&state=good-state",
      statePayload({ provider: "google", codeVerifier: "the-verifier" }),
    );

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://api.internal:8080/api/v1/auth/oauth/google");
    expect(JSON.parse(init.body as string)).toEqual({
      code: "provider-code",
      redirectUri: "https://ogu.example/api/auth/oauth/google/callback",
      codeVerifier: "the-verifier",
    });
  });

  it("US3-AC2 온보딩을 마친 계정이면 ogu_ob를 심고 쿠키에 담긴 next로 보낸다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(true), error: null }),
    );

    const response = await callback(
      "kakao",
      "?code=provider-code&state=good-state",
      statePayload({ next: "/my" }),
    );

    expectRedirect(response, "/my");
    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
    expectStateCookieCleared(response);
  });

  it("US3-AC2 next가 없으면 /home으로 보낸다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(true), error: null }),
    );

    const response = await callback(
      "kakao",
      "?code=provider-code&state=good-state",
    );

    expectRedirect(response, "/home");
  });

  it("리다이렉트 주소 어디에도 토큰이 없다(FR-012)", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(true), error: null }),
    );

    const response = await callback(
      "kakao",
      "?code=provider-code&state=good-state",
    );

    expect(response.headers.get("location")).not.toContain("token");
    expect(await response.text()).not.toContain("access-token");
  });

  it("US3-AC4 동의를 취소하면(error=access_denied) /login?error=oauth_cancelled로 보내고 API를 부르지 않는다", async () => {
    const response = await callback(
      "kakao",
      "?error=access_denied&error_description=User%20denied&state=good-state",
    );

    expectRedirect(response, "/login?error=oauth_cancelled");
    expect(fetchMock).not.toHaveBeenCalled();
    expectStateCookieCleared(response);
  });

  it("제공자가 다른 오류를 돌려주면 /login?error=oauth_failed로 보낸다", async () => {
    const response = await callback(
      "kakao",
      "?error=server_error&state=good-state",
    );

    expectRedirect(response, "/login?error=oauth_failed");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["state가 다름", "?code=c&state=bad-state", statePayload()],
    ["state가 없음", "?code=c", statePayload()],
    ["쿠키가 없음", "?code=c&state=good-state", null],
    [
      "쿠키가 10분 넘게 지남",
      "?code=c&state=good-state",
      statePayload({ issuedAt: Date.now() - 601_000 }),
    ],
    [
      "쿠키의 제공자가 다름",
      "?code=c&state=good-state",
      statePayload({ provider: "google", codeVerifier: "v" }),
    ],
  ])(
    "%s이면 /login?error=oauth_failed로 보내고 API를 부르지 않는다",
    async (_label, query, cookiePayload) => {
      const response = await callback("kakao", query, cookiePayload);

      expectRedirect(response, "/login?error=oauth_failed");
      expect(fetchMock).not.toHaveBeenCalled();
      expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
      expectStateCookieCleared(response);
    },
  );

  it("state가 맞아도 code가 없으면 /login?error=oauth_failed다", async () => {
    const response = await callback("kakao", "?state=good-state");

    expectRedirect(response, "/login?error=oauth_failed");
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("state가 틀리면 error=access_denied여도 oauth_failed다(쿠키 대조가 먼저다)", async () => {
    const response = await callback("kakao", "?error=access_denied&state=bad");

    expectRedirect(response, "/login?error=oauth_failed");
  });

  it("지원하지 않는 제공자면 /login?error=oauth_failed로 보내고 상태 쿠키를 지운다", async () => {
    const response = await callback("naver", "?code=c&state=good-state");

    expectRedirect(response, "/login?error=oauth_failed");
    expect(fetchMock).not.toHaveBeenCalled();
    expectStateCookieCleared(response);
  });

  it("US3-AC3 API가 409(EMAIL_REGISTERED_WITH_OTHER_METHOD)면 /login?error=email_registered로 보낸다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: {
            code: "EMAIL_REGISTERED_WITH_OTHER_METHOD",
            message: "이미 이메일로 가입한 주소입니다.",
          },
        },
        409,
      ),
    );

    const response = await callback("kakao", "?code=c&state=good-state");

    expectRedirect(response, "/login?error=email_registered");
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
    expectStateCookieCleared(response);
  });

  it.each([
    [400, "INVALID_REQUEST"],
    [401, "OAUTH_CODE_INVALID"],
    [502, "OAUTH_PROVIDER_UNAVAILABLE"],
  ])(
    "API가 %i(%s)면 /login?error=oauth_failed로 보낸다",
    async (status, code) => {
      fetchMock.mockResolvedValue(
        jsonResponse(
          { success: false, data: null, error: { code, message: "실패" } },
          status,
        ),
      );

      const response = await callback("kakao", "?code=c&state=good-state");

      expectRedirect(response, "/login?error=oauth_failed");
      expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
      expectStateCookieCleared(response);
    },
  );

  it("API에 연결할 수 없으면 /login?error=oauth_failed로 보낸다", async () => {
    fetchMock.mockRejectedValue(new TypeError("fetch failed"));

    const response = await callback("kakao", "?code=c&state=good-state");

    expectRedirect(response, "/login?error=oauth_failed");
  });

  it("API가 2xx인데 본문이 비어 있으면 /login?error=oauth_failed로 보낸다", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 200 }));

    const response = await callback("kakao", "?code=c&state=good-state");

    expectRedirect(response, "/login?error=oauth_failed");
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)).toBeUndefined();
  });

  it("응답은 캐시하지 않고 Referer로 code가 새지 않게 한다", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ success: true, data: authResult(true), error: null }),
    );

    const response = await callback("kakao", "?code=c&state=good-state");

    expect(response.headers.get("cache-control")).toContain("no-store");
    expect(response.headers.get("referrer-policy")).toBe("no-referrer");
  });
});
