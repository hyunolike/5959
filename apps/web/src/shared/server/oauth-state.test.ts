// @vitest-environment node
import { NextResponse } from "next/server";
import { describe, expect, it } from "vitest";

import {
  buildAuthorizationUrl,
  clearOAuthStateCookie,
  codeChallengeS256,
  createOAuthState,
  generateRandomToken,
  isOAuthProvider,
  OAUTH_STATE_COOKIE,
  OAUTH_STATE_MAX_AGE_SECONDS,
  oauthRedirectUri,
  sanitizeNextPath,
  serializeOAuthState,
  setOAuthStateCookie,
  verifyOAuthCallback,
  type OAuthStatePayload,
} from "./oauth-state";

const SECRET = "test-oauth-state-secret-0123456789abcdef";
const NOW = 1_700_000_000_000;

function payload(
  overrides: Partial<OAuthStatePayload> = {},
): OAuthStatePayload {
  return {
    provider: "google",
    state: "state-value",
    codeVerifier: "verifier-value",
    next: "/home",
    issuedAt: NOW,
    ...overrides,
  };
}

describe("generateRandomToken", () => {
  it("32바이트 난수를 base64url(패딩 없음, 43자)로 만든다", () => {
    const token = generateRandomToken();

    expect(token).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(Buffer.from(token, "base64url")).toHaveLength(32);
  });

  it("부를 때마다 다른 값을 만든다", () => {
    expect(generateRandomToken()).not.toBe(generateRandomToken());
  });
});

describe("codeChallengeS256", () => {
  it("base64url(SHA-256(verifier))를 돌려준다(RFC 7636 부록 B 예시)", () => {
    expect(
      codeChallengeS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
    ).toBe("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
  });
});

describe("isOAuthProvider", () => {
  it("kakao와 google만 지원한다", () => {
    expect(isOAuthProvider("kakao")).toBe(true);
    expect(isOAuthProvider("google")).toBe(true);
    expect(isOAuthProvider("naver")).toBe(false);
    expect(isOAuthProvider("KAKAO")).toBe(false);
    expect(isOAuthProvider("__proto__")).toBe(false);
  });
});

describe("createOAuthState", () => {
  it("구글은 state와 code_verifier를 32바이트 base64url로 만든다", () => {
    const created = createOAuthState("google", "/my", NOW);

    expect(created.provider).toBe("google");
    expect(created.state).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.codeVerifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.state).not.toBe(created.codeVerifier);
    expect(created.next).toBe("/my");
    expect(created.issuedAt).toBe(NOW);
  });

  it("카카오는 PKCE를 쓰지 않으므로 code_verifier가 없다", () => {
    const created = createOAuthState("kakao", "/home", NOW);

    expect(created.state).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(created.codeVerifier).toBeNull();
  });
});

describe("serializeOAuthState와 verifyOAuthCallback", () => {
  it("직렬화한 쿠키 값에 state와 code_verifier가 평문 JSON으로 드러나지 않는 형태(본문.서명)다", () => {
    const value = serializeOAuthState(payload(), SECRET);

    expect(value).toMatch(/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{43}$/);
  });

  it("같은 제공자, 같은 state면 저장한 값을 돌려준다", () => {
    const value = serializeOAuthState(payload(), SECRET);

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW + 1000,
      ),
    ).toEqual(payload());
  });

  it("state가 다르면 거절한다", () => {
    const value = serializeOAuthState(payload(), SECRET);

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "other-state" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("state가 없으면 거절한다", () => {
    const value = serializeOAuthState(payload(), SECRET);

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: null },
        SECRET,
        NOW,
      ),
    ).toBeNull();
    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("쿠키가 없으면 거절한다", () => {
    expect(
      verifyOAuthCallback(
        undefined,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("쿠키를 만든 제공자와 콜백 제공자가 다르면 거절한다", () => {
    const value = serializeOAuthState(payload({ provider: "kakao" }), SECRET);

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("10분이 지나면 거절하고, 10분 안이면 받아들인다", () => {
    const value = serializeOAuthState(payload(), SECRET);
    const callback = { provider: "google" as const, state: "state-value" };

    expect(OAUTH_STATE_MAX_AGE_SECONDS).toBe(600);
    expect(
      verifyOAuthCallback(value, callback, SECRET, NOW + 600_000),
    ).not.toBeNull();
    expect(
      verifyOAuthCallback(value, callback, SECRET, NOW + 600_001),
    ).toBeNull();
  });

  it("발급 시각이 미래면(시계를 조작한 쿠키) 거절한다", () => {
    const value = serializeOAuthState(
      payload({ issuedAt: NOW + 60_000 }),
      SECRET,
    );

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("다른 비밀 키로 서명한 쿠키는 거절한다", () => {
    const value = serializeOAuthState(
      payload(),
      "another-secret-0123456789abcdef",
    );

    expect(
      verifyOAuthCallback(
        value,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it("본문을 바꾼 쿠키(next를 외부 주소로 바꾸는 등)는 서명이 맞지 않아 거절한다", () => {
    const value = serializeOAuthState(payload(), SECRET);
    const [, signature] = value.split(".");
    const forgedBody = Buffer.from(
      JSON.stringify({ ...payload(), next: "//evil.example" }),
    ).toString("base64url");

    expect(
      verifyOAuthCallback(
        `${forgedBody}.${signature}`,
        { provider: "google", state: "state-value" },
        SECRET,
        NOW,
      ),
    ).toBeNull();
  });

  it.each(["", "no-dot", "a.b.c", ".", "body.", "!!!.???"])(
    "형식이 깨진 쿠키 값(%j)은 예외 없이 거절한다",
    (value) => {
      expect(
        verifyOAuthCallback(
          value,
          { provider: "google", state: "state-value" },
          SECRET,
          NOW,
        ),
      ).toBeNull();
    },
  );
});

describe("sanitizeNextPath", () => {
  it.each([
    ["/home", "/home"],
    ["/my/posts?tab=1", "/my/posts?tab=1"],
    ["/write#draft", "/write#draft"],
  ])("같은 출처 경로 %j는 그대로 쓴다", (next, expected) => {
    expect(sanitizeNextPath(next)).toBe(expected);
  });

  it.each([
    [null],
    [undefined],
    [""],
    ["home"],
    ["//evil.example"],
    ["/\\evil.example"],
    ["https://evil.example/home"],
    ["javascript:alert(1)"],
    ["/\t/evil.example"],
    ["/\n/evil.example"],
    [" /home"],
    [`/${"a".repeat(1024)}`],
  ])("안전하지 않은 값 %j는 /home으로 바꾼다", (next) => {
    expect(sanitizeNextPath(next)).toBe("/home");
  });
});

describe("oauthRedirectUri", () => {
  it("APP_ORIGIN + /api/auth/oauth/{provider}/callback이다", () => {
    expect(oauthRedirectUri("kakao", "https://ogu.example")).toBe(
      "https://ogu.example/api/auth/oauth/kakao/callback",
    );
    expect(oauthRedirectUri("google", "http://localhost:3000/")).toBe(
      "http://localhost:3000/api/auth/oauth/google/callback",
    );
  });
});

describe("buildAuthorizationUrl", () => {
  const redirectUri = "https://ogu.example/api/auth/oauth/kakao/callback";

  it("카카오: account_email scope와 state를 담고 PKCE 파라미터는 없다", () => {
    const url = new URL(
      buildAuthorizationUrl(
        payload({ provider: "kakao", codeVerifier: null }),
        {
          clientId: "kakao-client",
          redirectUri,
        },
      ),
    );

    expect(url.origin + url.pathname).toBe(
      "https://kauth.kakao.com/oauth/authorize",
    );
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("client_id")).toBe("kakao-client");
    expect(url.searchParams.get("redirect_uri")).toBe(redirectUri);
    expect(url.searchParams.get("state")).toBe("state-value");
    expect(url.searchParams.get("scope")).toBe("account_email");
    expect(url.searchParams.has("code_challenge")).toBe(false);
  });

  it("구글: openid email scope, state, S256 code_challenge를 담는다", () => {
    const googleRedirect = "https://ogu.example/api/auth/oauth/google/callback";
    const url = new URL(
      buildAuthorizationUrl(payload(), {
        clientId: "google-client",
        redirectUri: googleRedirect,
      }),
    );

    expect(url.origin + url.pathname).toBe(
      "https://accounts.google.com/o/oauth2/v2/auth",
    );
    expect(url.searchParams.get("response_type")).toBe("code");
    expect(url.searchParams.get("client_id")).toBe("google-client");
    expect(url.searchParams.get("redirect_uri")).toBe(googleRedirect);
    expect(url.searchParams.get("state")).toBe("state-value");
    expect(url.searchParams.get("scope")).toBe("openid email");
    expect(url.searchParams.get("code_challenge")).toBe(
      codeChallengeS256("verifier-value"),
    );
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
  });
});

describe("OAuth 상태 쿠키", () => {
  it("__Host-ogu_oauth를 HttpOnly, Secure, SameSite=Lax, Path=/, Max-Age=600으로 심는다", () => {
    const response = NextResponse.redirect("https://ogu.example/x", 302);

    setOAuthStateCookie(response, "value");

    const cookie = response.cookies.get(OAUTH_STATE_COOKIE);
    expect(OAUTH_STATE_COOKIE).toBe("__Host-ogu_oauth");
    expect(cookie?.value).toBe("value");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
    expect(cookie?.maxAge).toBe(600);
  });

  it("지울 때는 같은 속성에 Max-Age=0으로 덮는다", () => {
    const response = NextResponse.redirect("https://ogu.example/x", 302);

    clearOAuthStateCookie(response);

    const cookie = response.cookies.get(OAUTH_STATE_COOKIE);
    expect(cookie?.value).toBe("");
    expect(cookie?.maxAge).toBe(0);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.path).toBe("/");
  });
});
