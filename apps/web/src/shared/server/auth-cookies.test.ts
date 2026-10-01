// @vitest-environment node
import { NextRequest, NextResponse } from "next/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import {
  ACCESS_TOKEN_COOKIE,
  clearOnboardedCookie,
  clearSessionCookies,
  ONBOARDED_COOKIE,
  readSessionCookies,
  REFRESH_TOKEN_COOKIE,
  setOnboardedCookie,
  setSessionCookies,
} from "./auth-cookies";

function futureIso(seconds: number): string {
  return new Date(Date.now() + seconds * 1000).toISOString();
}

describe("setSessionCookies", () => {
  it("access 토큰 쿠키를 __Host- 접두사, HttpOnly, Secure, SameSite=Lax, Max-Age=900으로 설정한다", () => {
    const response = NextResponse.json({});

    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: futureIso(1000),
    });

    const cookie = response.cookies.get(ACCESS_TOKEN_COOKIE);
    expect(cookie?.value).toBe("access-token");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
    expect(cookie?.maxAge).toBe(900);
  });

  it("refresh 토큰 쿠키의 Max-Age를 refreshTokenExpiresAt까지 남은 초로 설정한다", () => {
    const response = NextResponse.json({});

    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: futureIso(3600),
    });

    const cookie = response.cookies.get(REFRESH_TOKEN_COOKIE);
    expect(cookie?.value).toBe("refresh-token");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
    expect(cookie?.maxAge).toBeGreaterThan(3595);
    expect(cookie?.maxAge).toBeLessThanOrEqual(3600);
  });

  it("US4-AC2 refreshToken이 null이면(유예 구간) ogu_rt 쿠키를 건드리지 않는다", () => {
    const response = NextResponse.json({});

    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: null,
      refreshTokenExpiresAt: futureIso(3600),
    });

    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "access-token",
    );
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)).toBeUndefined();
  });

  it("이미 지난 만료 시각이면 Max-Age를 0으로 설정한다", () => {
    const response = NextResponse.json({});

    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: new Date(Date.now() - 60_000).toISOString(),
    });

    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.maxAge).toBe(0);
  });

  it("만료 시각을 해석할 수 없으면 Max-Age를 0으로 설정하고 콘솔에 남긴다", () => {
    const consoleError = vi
      .spyOn(console, "error")
      .mockImplementation(() => undefined);
    const response = NextResponse.json({});

    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: "이건 날짜가 아니다",
    });

    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.maxAge).toBe(0);
    expect(consoleError).toHaveBeenCalledTimes(1);
    consoleError.mockRestore();
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("setOnboardedCookie", () => {
  it("온보딩 완료 쿠키를 값 1로, HttpOnly, Secure, SameSite=Lax로 설정한다", () => {
    const response = NextResponse.json({});

    setOnboardedCookie(response);

    const cookie = response.cookies.get(ONBOARDED_COOKIE);
    expect(cookie?.value).toBe("1");
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.secure).toBe(true);
    expect(cookie?.sameSite).toBe("lax");
    expect(cookie?.path).toBe("/");
  });

  it("refreshTokenExpiresAt을 주면 그 시각까지 남은 초를 Max-Age로 설정한다(브라우저를 재시작해도 유지)", () => {
    const response = NextResponse.json({});

    setOnboardedCookie(response, futureIso(3600));

    const cookie = response.cookies.get(ONBOARDED_COOKIE);
    expect(cookie?.maxAge).toBeGreaterThan(3595);
    expect(cookie?.maxAge).toBeLessThanOrEqual(3600);
  });

  it("refreshTokenExpiresAt을 주지 않으면 Max-Age를 30일(절대 세션 한도)로 설정한다", () => {
    const response = NextResponse.json({});

    setOnboardedCookie(response);

    const cookie = response.cookies.get(ONBOARDED_COOKIE);
    expect(cookie?.maxAge).toBe(30 * 24 * 60 * 60);
  });
});

describe("clearOnboardedCookie", () => {
  it("온보딩 표시 쿠키만 지운다(Max-Age=0), 다른 쿠키는 건드리지 않는다", () => {
    const response = NextResponse.json({});
    setSessionCookies(response, {
      accessToken: "access-token",
      refreshToken: "refresh-token",
      refreshTokenExpiresAt: futureIso(3600),
    });

    clearOnboardedCookie(response);

    expect(response.cookies.get(ONBOARDED_COOKIE)?.value).toBe("");
    expect(response.cookies.get(ONBOARDED_COOKIE)?.maxAge).toBe(0);
    expect(response.cookies.get(ACCESS_TOKEN_COOKIE)?.value).toBe(
      "access-token",
    );
    expect(response.cookies.get(REFRESH_TOKEN_COOKIE)?.value).toBe(
      "refresh-token",
    );
  });
});

describe("readSessionCookies", () => {
  it("요청에 실린 access, refresh, 온보딩 쿠키를 읽는다", () => {
    const request = new NextRequest("http://localhost/api/members/me", {
      headers: {
        cookie: `${ACCESS_TOKEN_COOKIE}=access-token; ${REFRESH_TOKEN_COOKIE}=refresh-token; ${ONBOARDED_COOKIE}=1`,
      },
    });

    expect(readSessionCookies(request)).toEqual({
      accessToken: "access-token",
      refreshToken: "refresh-token",
      onboarded: true,
    });
  });

  it("쿠키가 없으면 undefined와 onboarded=false를 돌려준다", () => {
    const request = new NextRequest("http://localhost/api/members/me");

    expect(readSessionCookies(request)).toEqual({
      accessToken: undefined,
      refreshToken: undefined,
      onboarded: false,
    });
  });
});

describe("clearSessionCookies", () => {
  it("세 쿠키를 모두 지운다(Max-Age=0)", () => {
    const response = NextResponse.json({});

    clearSessionCookies(response);

    for (const name of [
      ACCESS_TOKEN_COOKIE,
      REFRESH_TOKEN_COOKIE,
      ONBOARDED_COOKIE,
    ]) {
      const cookie = response.cookies.get(name);
      expect(cookie?.value).toBe("");
      expect(cookie?.maxAge).toBe(0);
    }
  });
});
