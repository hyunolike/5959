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

    const requestBody = JSON.stringify({ nickname: "오구" });
    const request = new NextRequest(
      "http://localhost:3000/api/members/nickname-availability",
      {
        method: "POST",
        headers: {
          origin: "http://localhost:3000",
          "content-type": "application/json",
        },
        body: requestBody,
      },
    );
    await POST(request, params(["members", "nickname-availability"]));

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBe(requestBody);
    const headers = init.headers as Headers;
    expect(headers.get("content-type")).toBe("application/json");
  });
});

describe("전용 라우트만 다뤄야 하는 경로는 404로 막는다(FR-012)", () => {
  it("auth로 시작하는 경로는 GET도 막는다(오면 catch-all이 토큰을 그대로 흘릴 수 있다)", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest(
      "http://localhost:3000/api/auth/oauth/kakao",
    );
    const response = await GET(request, params(["auth", "oauth", "kakao"]));

    expect(response.status).toBe(404);
    await expect(response.json()).resolves.toEqual({
      success: false,
      data: null,
      error: { code: "NOT_FOUND", message: expect.any(String) },
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["auth", "login"],
    ["auth", "signup"],
    ["auth", "refresh"],
  ])(
    "POST /api/%s/%s는 404로 막는다(accessToken/refreshToken 노출 방지)",
    async (...segments) => {
      const fetchMock = vi.fn();
      vi.stubGlobal("fetch", fetchMock);

      const request = new NextRequest(
        `http://localhost:3000/api/${segments.join("/")}`,
        { method: "POST", headers: { origin: "http://localhost:3000" } },
      );
      const response = await POST(request, params(segments));

      expect(response.status).toBe(404);
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("PUT /api/members/me/onboarding은 404로 막는다(accessToken 노출 방지)", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest(
      "http://localhost:3000/api/members/me/onboarding",
      { method: "PUT", headers: { origin: "http://localhost:3000" } },
    );
    const response = await PUT(
      request,
      params(["members", "me", "onboarding"]),
    );

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("대소문자를 바꿔도(GET /api/Auth/login) 404로 막는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/Auth/login");
    const response = await GET(request, params(["Auth", "login"]));

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("대소문자를 바꿔도(PUT /api/MEMBERS/me/onboarding) 404로 막는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest(
      "http://localhost:3000/api/MEMBERS/me/onboarding",
      { method: "PUT", headers: { origin: "http://localhost:3000" } },
    );
    const response = await PUT(
      request,
      params(["MEMBERS", "me", "onboarding"]),
    );

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("인코딩된 변형(auth%2Flogin을 한 세그먼트로)도 404로 막는다", async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);

    // Next.js가 "auth%2Flogin"을 하나의 세그먼트로 디코딩하면 "auth/login"이
    // 되어 이 배열의 값과 같아진다.
    const request = new NextRequest("http://localhost:3000/api/auth%2Flogin", {
      method: "POST",
      headers: { origin: "http://localhost:3000" },
    });
    const response = await POST(request, params(["auth/login"]));

    expect(response.status).toBe(404);
    expect(fetchMock).not.toHaveBeenCalled();
  });
});

describe("경로 조작/주입 방지(Important 1)", () => {
  it.each([
    ["상위 디렉터리(..)", ".."],
    ["이중 인코딩된 상위 디렉터리(%2e%2e)", "%2e%2e"],
    ["세그먼트 안에 숨은 슬래시(%2F)", "actuator%2Fhealth"],
    ["세그먼트 안에 숨은 물음표(%3F)", "a%3Fb"],
    ["세그먼트 안에 숨은 해시(%23)", "a%23b"],
    ["백슬래시", "a\\b"],
  ])(
    "%s 세그먼트는 404로 막고 API를 부르지 않는다",
    async (_label, segment) => {
      const fetchMock = vi.fn();
      vi.stubGlobal("fetch", fetchMock);

      const request = new NextRequest("http://localhost:3000/api/x");
      const response = await GET(request, params(["x", segment]));

      expect(response.status).toBe(404);
      await expect(response.json()).resolves.toEqual({
        success: false,
        data: null,
        error: { code: "NOT_FOUND", message: expect.any(String) },
      });
      expect(fetchMock).not.toHaveBeenCalled();
    },
  );

  it("정상 세그먼트는 encodeURIComponent로 다시 인코딩해 전달한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: null, error: null }),
      );
    vi.stubGlobal("fetch", fetchMock);

    const request = new NextRequest("http://localhost:3000/api/members/me");
    await GET(request, params(["members", "me"]));

    const [url] = fetchMock.mock.calls[0] as [string];
    expect(new URL(url).pathname).toBe("/api/v1/members/me");
  });
});

describe("업스트림이 본문 없이 응답하면(callApi가 body: null을 돌려줄 때)", () => {
  it("상태만 그대로 전달하고 JSON 본문을 만들지 않는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );

    const request = new NextRequest("http://localhost:3000/api/members/me");
    const response = await GET(request, params(["members", "me"]));

    expect(response.status).toBe(204);
    const text = await response.text();
    expect(text).toBe("");
  });
});

const API_REFRESH_URL = "http://api.internal:8080/api/v1/auth/refresh";
const API_ME_URL = "http://api.internal:8080/api/v1/members/me";

function refreshedBody({
  refreshToken = "new-refresh",
  onboarded = true,
}: { refreshToken?: string | null; onboarded?: boolean } = {}) {
  return {
    success: true,
    data: {
      member: { id: 1, onboarded },
      tokens: {
        accessToken: "new-access",
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

const unauthorizedBody = (code = "UNAUTHORIZED") => ({
  success: false,
  data: null,
  error: { code, message: "인증이 필요합니다." },
});

const ME_BODY = { success: true, data: { id: 1 }, error: null };

function requestWithCookies(
  cookies: Record<string, string>,
  init: ConstructorParameters<typeof NextRequest>[1] = {},
) {
  const cookie = Object.entries(cookies)
    .map(([name, value]) => `${name}=${value}`)
    .join("; ");
  return new NextRequest("http://localhost:3000/api/members/me", {
    ...init,
    headers: { ...(init.headers as Record<string, string>), cookie },
  });
}

function callsTo(fetchMock: ReturnType<typeof vi.fn>, url: string) {
  return fetchMock.mock.calls.filter(([calledUrl]) => calledUrl === url);
}

function authorizationOf(call: unknown[]): string | null {
  return ((call[1] as RequestInit).headers as Headers).get("Authorization");
}

describe("세션 갱신(US4-AC1, bff-routes.md 범용 프록시)", () => {
  it("API가 401이면 ogu_rt로 refresh한 뒤 새 access 토큰으로 원래 요청을 한 번 다시 보내 성공한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(jsonResponse(ME_BODY));
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual(ME_BODY);
    const urls = fetchMock.mock.calls.map(([url]) => url);
    expect(urls).toEqual([API_ME_URL, API_REFRESH_URL, API_ME_URL]);
    expect(authorizationOf(fetchMock.mock.calls[0])).toBe(
      "Bearer expired-access",
    );
    expect(authorizationOf(fetchMock.mock.calls[2])).toBe("Bearer new-access");
    const [, refreshInit] = fetchMock.mock.calls[1] as [string, RequestInit];
    expect(refreshInit.method).toBe("POST");
    expect(JSON.parse(refreshInit.body as string)).toEqual({
      refreshToken: "old-refresh",
    });
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe("new-access");
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "new-refresh",
    );
    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("1");
  });

  it("다시 보낸 요청에도 원래 메서드와 본문을 그대로 싣는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(jsonResponse(ME_BODY));
    vi.stubGlobal("fetch", fetchMock);
    const requestBody = JSON.stringify({ title: "고민" });

    await POST(
      requestWithCookies(
        {
          [ACCESS_TOKEN_COOKIE]: "expired-access",
          [REFRESH_TOKEN_COOKIE]: "old-refresh",
        },
        {
          method: "POST",
          headers: {
            origin: "http://localhost:3000",
            "content-type": "application/json",
          },
          body: requestBody,
        },
      ),
      params(["posts"]),
    );

    const [, retried] = fetchMock.mock.calls[2] as [string, RequestInit];
    expect(retried.method).toBe("POST");
    expect(retried.body).toBe(requestBody);
  });

  it("refresh 응답의 refreshToken이 null이면(유예 구간) ogu_rt는 건드리지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockResolvedValueOnce(
        jsonResponse(refreshedBody({ refreshToken: null })),
      )
      .mockResolvedValueOnce(jsonResponse(ME_BODY));
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(200);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe("new-access");
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)).toBeUndefined();
    expect(response.headers.getSetCookie().join("\n")).not.toContain(
      REFRESH_TOKEN_COOKIE,
    );
  });

  it("갱신한 회원이 온보딩 전이면 ogu_ob를 지운다", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
        .mockResolvedValueOnce(
          jsonResponse(refreshedBody({ onboarded: false })),
        )
        .mockResolvedValueOnce(jsonResponse(ME_BODY)),
    );

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
        [ONBOARDED_COOKIE]: "1",
      }),
      params(["members", "me"]),
    );

    const onboarded = response.cookies.get(ONBOARDED_COOKIE);
    expect(onboarded?.value).toBe("");
    expect(onboarded?.maxAge).toBe(0);
  });

  it("refresh도 실패하면 세 쿠키를 모두 지우고 401을 돌려주며 원래 요청은 다시 보내지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockResolvedValueOnce(
        jsonResponse(unauthorizedBody("SESSION_EXPIRED"), 401),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "stolen-or-expired",
        [ONBOARDED_COOKIE]: "1",
      }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
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

  it("ogu_at이 없고 ogu_rt만 있으면 먼저 refresh한 뒤 새 access 토큰으로 요청한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValueOnce(jsonResponse(ME_BODY));
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({ [REFRESH_TOKEN_COOKIE]: "old-refresh" }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(200);
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
      API_REFRESH_URL,
      API_ME_URL,
    ]);
    expect(authorizationOf(fetchMock.mock.calls[1])).toBe("Bearer new-access");
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "new-refresh",
    );
  });

  it("먼저 refresh한 뒤에도 API가 401이면 다시 refresh하지 않고 세 쿠키를 지운 401로 끝낸다(갱신 반복 방지)", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValue(
        jsonResponse(unauthorizedBody("SESSION_EXPIRED"), 401),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({ [REFRESH_TOKEN_COOKIE]: "old-refresh" }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(401);
    expect(callsTo(fetchMock, API_REFRESH_URL)).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.maxAge).toBe(0);
  });

  it("다시 보낸 요청이 또 401이어도 refresh는 한 번뿐이다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockResolvedValueOnce(jsonResponse(refreshedBody()))
      .mockResolvedValue(
        jsonResponse(unauthorizedBody("SESSION_EXPIRED"), 401),
      );
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(401);
    expect(callsTo(fetchMock, API_REFRESH_URL)).toHaveLength(1);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    for (const name of [
      ACCESS_TOKEN_COOKIE,
      REFRESH_TOKEN_COOKIE,
      ONBOARDED_COOKIE,
    ]) {
      expect(response.cookies.get(name)?.maxAge).toBe(0);
    }
  });

  it("ogu_rt가 없으면 401을 그대로 돌려주고 refresh를 부르지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse(unauthorizedBody(), 401));
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({ [ACCESS_TOKEN_COOKIE]: "expired-access" }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledOnce();
  });

  it("refresh 호출이 API 장애(502)로 실패하면 쿠키를 지우지 않고 그 오류를 돌려준다", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(unauthorizedBody(), 401))
      .mockRejectedValueOnce(new TypeError("fetch failed"));
    vi.stubGlobal("fetch", fetchMock);

    const response = await GET(
      requestWithCookies({
        [ACCESS_TOKEN_COOKIE]: "expired-access",
        [REFRESH_TOKEN_COOKIE]: "old-refresh",
      }),
      params(["members", "me"]),
    );

    expect(response.status).toBe(502);
    expect(response.headers.getSetCookie()).toEqual([]);
  });

  it("응답 본문과 쿠키 밖 어디에도 새 토큰을 싣지 않는다(FR-012)", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(jsonResponse(refreshedBody()))
        .mockResolvedValueOnce(jsonResponse(ME_BODY)),
    );

    const response = await GET(
      requestWithCookies({ [REFRESH_TOKEN_COOKIE]: "old-refresh" }),
      params(["members", "me"]),
    );

    const text = await response.text();
    expect(text).not.toContain("new-access");
    expect(text).not.toContain("new-refresh");
  });
});
