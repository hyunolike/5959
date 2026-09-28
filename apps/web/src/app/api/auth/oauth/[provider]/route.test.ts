// @vitest-environment node
import { NextRequest } from "next/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mockEnv = vi.hoisted(() => ({
  API_ORIGIN: "http://api.internal:8080",
  BFF_API_KEY: "test-bff-key",
  APP_ORIGIN: "https://ogu.example",
  APP_ENV: "development" as "development" | "e2e" | "production",
  KAKAO_CLIENT_ID: "kakao-client" as string | undefined,
  GOOGLE_CLIENT_ID: "google-client" as string | undefined,
  OAUTH_STATE_SECRET: "test-oauth-state-secret-0123456789abcdef",
}));

vi.mock("@/shared/config", () => ({ env: mockEnv }));

import {
  OAUTH_STATE_COOKIE,
  verifyOAuthCallback,
  type OAuthProvider,
} from "@/shared/server";

import { GET } from "./route";

function start(provider: string, query = "") {
  const request = new NextRequest(
    `https://ogu.example/api/auth/oauth/${provider}${query}`,
  );
  return GET(request, { params: Promise.resolve({ provider }) });
}

/** 쿠키에 담긴 상태를 서명 검증을 거쳐 꺼낸다. */
function storedState(
  response: Response & {
    cookies: { get(name: string): { value: string } | undefined };
  },
  provider: OAuthProvider,
  state: string,
) {
  return verifyOAuthCallback(
    response.cookies.get(OAUTH_STATE_COOKIE)?.value,
    { provider, state },
    mockEnv.OAUTH_STATE_SECRET,
  );
}

beforeEach(() => {
  mockEnv.APP_ENV = "development";
  mockEnv.KAKAO_CLIENT_ID = "kakao-client";
  mockEnv.GOOGLE_CLIENT_ID = "google-client";
});

describe("GET /api/auth/oauth/{provider}", () => {
  it("지원하지 않는 제공자면 302 /login이고 상태 쿠키를 심지 않는다", async () => {
    const response = await start("naver");

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe("https://ogu.example/login");
    expect(response.cookies.get(OAUTH_STATE_COOKIE)).toBeUndefined();
  });

  it("카카오: 302로 카카오 인가 URL에 보내고 state를 서명한 쿠키에 담는다", async () => {
    const response = await start("kakao", "?next=%2Fmy");

    expect(response.status).toBe(302);
    const location = new URL(response.headers.get("location")!);
    expect(location.origin + location.pathname).toBe(
      "https://kauth.kakao.com/oauth/authorize",
    );
    expect(location.searchParams.get("client_id")).toBe("kakao-client");
    expect(location.searchParams.get("redirect_uri")).toBe(
      "https://ogu.example/api/auth/oauth/kakao/callback",
    );
    expect(location.searchParams.get("scope")).toBe("account_email");
    expect(location.searchParams.has("code_challenge")).toBe(false);

    const state = location.searchParams.get("state")!;
    const stored = storedState(response, "kakao", state);
    expect(stored).toMatchObject({
      provider: "kakao",
      state,
      codeVerifier: null,
      next: "/my",
    });

    const cookie = response.cookies.get(OAUTH_STATE_COOKIE);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
    expect(cookie?.maxAge).toBe(600);
    expect(response.headers.get("cache-control")).toContain("no-store");
  });

  it("구글: 인가 URL의 code_challenge가 쿠키의 code_verifier에서 나온다", async () => {
    const { codeChallengeS256 } = await import("@/shared/server");
    const response = await start("google");

    const location = new URL(response.headers.get("location")!);
    expect(location.origin + location.pathname).toBe(
      "https://accounts.google.com/o/oauth2/v2/auth",
    );
    expect(location.searchParams.get("scope")).toBe("openid email");
    expect(location.searchParams.get("code_challenge_method")).toBe("S256");

    const stored = storedState(
      response,
      "google",
      location.searchParams.get("state")!,
    );
    expect(stored?.codeVerifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(location.searchParams.get("code_challenge")).toBe(
      codeChallengeS256(stored!.codeVerifier!),
    );
    expect(stored?.next).toBe("/home");
  });

  it.each(["//evil.example", "https://evil.example", "/\\evil.example"])(
    "next가 외부로 나가는 값(%j)이면 쿠키에 /home을 담는다",
    async (next) => {
      const response = await start(
        "kakao",
        `?next=${encodeURIComponent(next)}`,
      );
      const location = new URL(response.headers.get("location")!);

      const stored = storedState(
        response,
        "kakao",
        location.searchParams.get("state")!,
      );
      expect(stored?.next).toBe("/home");
    },
  );

  it("제공자 client id가 설정되지 않았으면 /login?error=oauth_failed로 보낸다", async () => {
    mockEnv.GOOGLE_CLIENT_ID = undefined;
    vi.spyOn(console, "error").mockImplementation(() => {});

    const response = await start("google");

    expect(response.status).toBe(302);
    expect(response.headers.get("location")).toBe(
      "https://ogu.example/login?error=oauth_failed",
    );
    expect(response.cookies.get(OAUTH_STATE_COOKIE)).toBeUndefined();
  });

  it("APP_ENV가 e2e가 아니면 e2e 쿼리를 무시하고 실제 제공자로 보낸다", async () => {
    const response = await start(
      "kakao",
      "?e2e_id=u1&e2e_email=a%40b.com&e2e_outcome=cancel",
    );

    const location = new URL(response.headers.get("location")!);
    expect(location.origin).toBe("https://kauth.kakao.com");
  });

  describe("APP_ENV=e2e", () => {
    beforeEach(() => {
      mockEnv.APP_ENV = "e2e";
    });

    it("제공자 대신 자기 콜백으로 바로 보내고 code는 fake:<id>:-이다", async () => {
      const response = await start("kakao");

      const location = new URL(response.headers.get("location")!);
      expect(location.origin + location.pathname).toBe(
        "https://ogu.example/api/auth/oauth/kakao/callback",
      );
      expect(location.searchParams.get("code")).toMatch(
        /^fake:[A-Za-z0-9_-]+:-$/,
      );
      const state = location.searchParams.get("state")!;
      expect(storedState(response, "kakao", state)).not.toBeNull();
    });

    it("e2e_id와 e2e_email을 코드에 담는다", async () => {
      const response = await start(
        "google",
        "?e2e_id=user-1&e2e_email=user1%40example.com",
      );

      const location = new URL(response.headers.get("location")!);
      expect(location.searchParams.get("code")).toBe(
        "fake:user-1:user1@example.com",
      );
      // 구글은 e2e에서도 code_verifier를 만든다(API가 없으면 400을 준다).
      const stored = storedState(
        response,
        "google",
        location.searchParams.get("state")!,
      );
      expect(stored?.codeVerifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    });

    it("e2e_outcome=denied면 제공자가 거절하는 code=denied를 보낸다", async () => {
      const response = await start("kakao", "?e2e_outcome=denied");

      const location = new URL(response.headers.get("location")!);
      expect(location.searchParams.get("code")).toBe("denied");
    });

    it("e2e_outcome=cancel이면 동의 취소처럼 error=access_denied를 보낸다", async () => {
      const response = await start("kakao", "?e2e_outcome=cancel");

      const location = new URL(response.headers.get("location")!);
      expect(location.searchParams.get("error")).toBe("access_denied");
      expect(location.searchParams.has("code")).toBe(false);
      expect(location.searchParams.get("state")).not.toBeNull();
    });

    it("코드 형식을 깨는 e2e_id, e2e_email(콜론 포함)은 무시한다", async () => {
      const response = await start("kakao", "?e2e_id=a%3Ab&e2e_email=x%3Ay");

      const location = new URL(response.headers.get("location")!);
      expect(location.searchParams.get("code")).toMatch(
        /^fake:[A-Za-z0-9_-]+:-$/,
      );
      expect(location.searchParams.get("code")).not.toContain("a:b");
    });
  });
});
