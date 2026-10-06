import { describe, expect, it } from "vitest";

import { scrubBreadcrumb, scrubEvent } from "./scrub-event";
import type { ScrubbableBreadcrumb, ScrubbableEvent } from "./scrub-event";

/**
 * Sentry `beforeSend`/`beforeBreadcrumb`에 붙는 순수 함수 검증. 오구오구는
 * 쿠키(`__Host-ogu_*`), `Authorization`/`X-Ogu-Bff-Key` 헤더, `password` 필드,
 * OAuth 콜백의 `code`/`state` 쿼리 파라미터를 절대 Sentry로 보내면 안 된다.
 */
describe("scrubEvent", () => {
  it("US5-AC2 요청 헤더의 Cookie, Authorization, X-Ogu-Bff-Key, Set-Cookie를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/me",
        headers: {
          Cookie: "__Host-ogu_at=abcdef; __Host-ogu_rt=ghijkl",
          Authorization: "Bearer secret-token",
          "X-Ogu-Bff-Key": "bff-secret",
          "Set-Cookie": "__Host-ogu_ob=1",
          "User-Agent": "vitest",
        },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.headers).toEqual({
      Cookie: "[Filtered]",
      Authorization: "[Filtered]",
      "X-Ogu-Bff-Key": "[Filtered]",
      "Set-Cookie": "[Filtered]",
      "User-Agent": "vitest",
    });
  });

  it("US5-AC2 event.user를 통째로 지운다(ip_address 등 기본 수집 방지)", () => {
    const event: ScrubbableEvent = {
      user: {
        id: "1",
        ip_address: "203.0.113.1",
        email: "a@example.com",
        username: "감자",
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.user).toBeUndefined();
  });

  it("US5-AC2 요청의 env 필드(서버 환경 변수 통과값)를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/",
        env: { REMOTE_ADDR: "203.0.113.1" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.env).toBeUndefined();
  });

  it("US5-AC2 예외 스택 프레임의 지역 변수(vars)를 통째로 지운다(password 포함)", () => {
    const event: ScrubbableEvent = {
      exception: {
        values: [
          {
            type: "Error",
            stacktrace: {
              frames: [
                {
                  filename: "login.ts",
                  function: "login",
                  vars: { password: "hunter2", email: "a@example.com" },
                },
                { filename: "index.ts", function: "handler" },
              ],
            },
          },
        ],
      },
    };

    const scrubbed = scrubEvent(event);

    expect(
      scrubbed.exception?.values?.[0]?.stacktrace?.frames?.[0],
    ).not.toHaveProperty("vars");
    expect(
      scrubbed.exception?.values?.[0]?.stacktrace?.frames?.[0]?.filename,
    ).toBe("login.ts");
    expect(
      scrubbed.exception?.values?.[0]?.stacktrace?.frames?.[1],
    ).not.toHaveProperty("vars");
  });

  it("US5-AC2 threads의 스택 프레임 지역 변수(vars)도 통째로 지운다", () => {
    const event: ScrubbableEvent = {
      threads: {
        values: [
          {
            id: 0,
            stacktrace: {
              frames: [
                {
                  filename: "signup.ts",
                  vars: { password: "hunter2" },
                },
              ],
            },
          },
        ],
      },
    };

    const scrubbed = scrubEvent(event);

    expect(
      scrubbed.threads?.values?.[0]?.stacktrace?.frames?.[0],
    ).not.toHaveProperty("vars");
  });

  it("US5-AC2 요청의 cookies 필드를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/",
        cookies: { "__Host-ogu_at": "abcdef", "__Host-ogu_ob": "1" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.cookies).toBeUndefined();
  });

  it("US5-AC2 요청 본문 객체에 담긴 password 필드를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/auth/login",
        data: { email: "a@example.com", password: "hunter2" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.data).toEqual({
      email: "a@example.com",
      password: "[Filtered]",
    });
  });

  it("US5-AC2 요청 본문이 JSON 문자열이어도 password 필드를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/auth/signup",
        data: JSON.stringify({
          nickname: "감자",
          password: "hunter2",
        }),
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.data).toBe(
      JSON.stringify({ nickname: "감자", password: "[Filtered]" }),
    );
  });

  it("US5-AC2 중첩된 JSON 문자열 안의 password도 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/auth/login",
        data: JSON.stringify({
          credentials: { email: "a@example.com", password: "hunter2" },
        }),
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.data).toBe(
      JSON.stringify({
        credentials: { email: "a@example.com", password: "[Filtered]" },
      }),
    );
  });

  it("password가 아닌 필드와 정상적인 문자열 본문은 그대로 둔다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/me",
        data: "이건 그냥 문자열입니다",
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.data).toBe("이건 그냥 문자열입니다");
  });

  it("US5-AC2 breadcrumbs의 fetch 데이터에 담긴 password를 지운다", () => {
    const event: ScrubbableEvent = {
      breadcrumbs: [
        {
          category: "fetch",
          data: {
            method: "POST",
            url: "https://ogu.example/api/v1/auth/login",
            requestBody: { password: "hunter2" },
          },
        },
      ],
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.breadcrumbs?.[0]?.data?.requestBody).toEqual({
      password: "[Filtered]",
    });
  });

  it("US5-AC2 breadcrumbs의 fetch 데이터에 담긴 Cookie/Authorization 헤더를 지운다", () => {
    const event: ScrubbableEvent = {
      breadcrumbs: [
        {
          category: "fetch",
          data: {
            method: "GET",
            url: "https://ogu.example/api/v1/me",
            requestHeaders: {
              Cookie: "__Host-ogu_at=abcdef",
              Authorization: "Bearer secret-token",
            },
          },
        },
      ],
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.breadcrumbs?.[0]?.data?.requestHeaders).toEqual({
      Cookie: "[Filtered]",
      Authorization: "[Filtered]",
    });
  });

  it("extra에 담긴 password, cookie 필드를 지운다", () => {
    const event: ScrubbableEvent = {
      extra: {
        lastRequest: { password: "hunter2", cookie: "__Host-ogu_at=abcdef" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.extra).toEqual({
      lastRequest: { password: "[Filtered]", cookie: "[Filtered]" },
    });
  });

  it("contexts에 담긴 password 필드를 지운다", () => {
    const event: ScrubbableEvent = {
      contexts: {
        state: { password: "hunter2" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.contexts).toEqual({
      state: { password: "[Filtered]" },
    });
  });

  it("OAuth 콜백 URL의 code, state 쿼리 파라미터를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/auth/oauth/kakao/callback?code=auth-code&state=xyz&foo=bar",
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.url).toBe(
      "https://ogu.example/api/auth/oauth/kakao/callback?foo=bar",
    );
  });

  it("query_string이 문자열이면 code, state를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/auth/oauth/kakao/callback",
        query_string: "code=auth-code&state=xyz&foo=bar",
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.query_string).toBe("foo=bar");
  });

  it("query_string이 객체면 code, state 키를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/auth/oauth/kakao/callback",
        query_string: { code: "auth-code", state: "xyz", foo: "bar" },
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.query_string).toEqual({ foo: "bar" });
  });

  it("query_string이 [key, value] 배열이면 code, state 항목을 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/auth/oauth/kakao/callback",
        query_string: [
          ["code", "auth-code"],
          ["state", "xyz"],
          ["foo", "bar"],
        ],
      },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.query_string).toEqual([["foo", "bar"]]);
  });

  it("알림 스트림 주소의 일회용 ticket 쿼리 파라미터를 지운다", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://api.ogu.example/api/v1/notifications/stream?ticket=secret-ticket&lastEventId=17",
        query_string: { ticket: "secret-ticket", lastEventId: "17" },
      },
      breadcrumbs: [
        {
          category: "fetch",
          data: {
            method: "GET",
            url: "https://api.ogu.example/api/v1/notifications/stream?ticket=secret-ticket&lastEventId=17",
          },
        },
      ],
    };

    const scrubbed = scrubEvent(event);

    expect(JSON.stringify(scrubbed)).not.toContain("secret-ticket");
    expect(scrubbed.request?.query_string).toEqual({ lastEventId: "17" });
    expect(scrubbed.request?.url).toContain("lastEventId=17");
  });

  it("breadcrumbs의 fetch url에서도 code, state 쿼리 파라미터를 지운다", () => {
    const event: ScrubbableEvent = {
      breadcrumbs: [
        {
          category: "fetch",
          data: {
            method: "GET",
            url: "/api/auth/oauth/kakao/callback?code=auth-code&state=xyz&foo=bar",
          },
        },
      ],
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.breadcrumbs?.[0]?.data?.url).toBe(
      "/api/auth/oauth/kakao/callback?foo=bar",
    );
  });

  it("breadcrumbs의 url.query에서도 code, state를 지운다", () => {
    const event: ScrubbableEvent = {
      breadcrumbs: [
        {
          category: "fetch",
          data: {
            method: "GET",
            url: "/api/auth/oauth/kakao/callback",
            "url.query": "code=auth-code&state=xyz&foo=bar",
          },
        },
      ],
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.breadcrumbs?.[0]?.data?.["url.query"]).toBe("foo=bar");
  });

  it("code, state가 없는 URL은 그대로 둔다", () => {
    const event: ScrubbableEvent = {
      request: { url: "https://ogu.example/home?tab=all" },
    };

    const scrubbed = scrubEvent(event);

    expect(scrubbed.request?.url).toBe("https://ogu.example/home?tab=all");
  });

  it("민감한 값이 전혀 없으면 이벤트를 그대로 둔다(불필요한 변형 없음)", () => {
    const event: ScrubbableEvent = {
      request: { url: "https://ogu.example/home", headers: { Accept: "*/*" } },
    };

    expect(scrubEvent(event)).toEqual(event);
  });

  it("원본 이벤트 객체를 변형하지 않는다(순수 함수)", () => {
    const event: ScrubbableEvent = {
      request: {
        url: "https://ogu.example/api/v1/auth/login",
        headers: { Authorization: "Bearer secret-token" },
        data: { password: "hunter2" },
      },
    };
    const snapshotBefore = JSON.stringify(event);

    scrubEvent(event);

    expect(JSON.stringify(event)).toBe(snapshotBefore);
  });
});

describe("scrubBreadcrumb", () => {
  it("US5-AC2 fetch breadcrumb 데이터의 password, Cookie, Authorization을 지운다", () => {
    const breadcrumb: ScrubbableBreadcrumb = {
      category: "fetch",
      data: {
        method: "POST",
        url: "https://ogu.example/api/v1/auth/login",
        requestBody: JSON.stringify({ password: "hunter2" }),
        requestHeaders: { Authorization: "Bearer secret-token" },
      },
    };

    const scrubbed = scrubBreadcrumb(breadcrumb);

    expect(scrubbed.data?.requestBody).toBe(
      JSON.stringify({ password: "[Filtered]" }),
    );
    expect(scrubbed.data?.requestHeaders).toEqual({
      Authorization: "[Filtered]",
    });
  });

  it("US5-AC2 xhr breadcrumb 데이터의 password를 지운다", () => {
    const breadcrumb: ScrubbableBreadcrumb = {
      category: "xhr",
      data: {
        method: "POST",
        url: "https://ogu.example/api/v1/auth/signup",
        body: { password: "hunter2" },
      },
    };

    const scrubbed = scrubBreadcrumb(breadcrumb);

    expect(scrubbed.data?.body).toEqual({ password: "[Filtered]" });
  });

  it("data가 없는 breadcrumb은 그대로 둔다", () => {
    const breadcrumb: ScrubbableBreadcrumb = {
      category: "navigation",
      message: "/home으로 이동",
    };

    expect(scrubBreadcrumb(breadcrumb)).toEqual(breadcrumb);
  });

  it("원본 breadcrumb을 변형하지 않는다(순수 함수)", () => {
    const breadcrumb: ScrubbableBreadcrumb = {
      category: "fetch",
      data: { url: "/x?code=a&state=b", password: "hunter2" },
    };
    const snapshotBefore = JSON.stringify(breadcrumb);

    scrubBreadcrumb(breadcrumb);

    expect(JSON.stringify(breadcrumb)).toBe(snapshotBefore);
  });
});
