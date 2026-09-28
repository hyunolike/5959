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
