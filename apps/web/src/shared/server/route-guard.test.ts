import { describe, expect, it } from "vitest";

import { resolveRouteGuardAction } from "./route-guard";

const SIGNED_OUT = { hasRefreshToken: false, onboarded: false };
const NOT_ONBOARDED = { hasRefreshToken: true, onboarded: false };
const ONBOARDED = { hasRefreshToken: true, onboarded: true };
const PROTECTED_PATHS = ["/home", "/write", "/my", "/settings"];

/**
 * bff-routes.md "라우트 가드(proxy.ts)" 표의 행마다 한 묶음씩 검증한다.
 */
describe("resolveRouteGuardAction", () => {
  describe("표 1행: / + ogu_rt 있음 → /home", () => {
    it("로그인했으면 /에서 /home으로 보낸다", () => {
      expect(resolveRouteGuardAction("/", NOT_ONBOARDED)).toEqual({
        type: "redirect",
        to: "/home",
      });
      expect(resolveRouteGuardAction("/", ONBOARDED)).toEqual({
        type: "redirect",
        to: "/home",
      });
    });

    it("로그인하지 않았으면 /를 그대로 보여준다", () => {
      expect(resolveRouteGuardAction("/", SIGNED_OUT)).toBeNull();
    });
  });

  describe("표 2행: 보호 경로 + ogu_rt 없음 → /login?next=<원래 경로>", () => {
    it.each(PROTECTED_PATHS)(
      "US4-AC4 %s는 로그아웃 상태면 /login?next=로 보낸다",
      (pathname) => {
        expect(resolveRouteGuardAction(pathname, SIGNED_OUT)).toEqual({
          type: "redirect",
          to: `/login?next=${encodeURIComponent(pathname)}`,
        });
      },
    );

    it("하위 경로와 쿼리 문자열도 next에 그대로 담는다", () => {
      expect(
        resolveRouteGuardAction("/my/posts", SIGNED_OUT, "?tab=1&x=%2F"),
      ).toEqual({
        type: "redirect",
        to: `/login?next=${encodeURIComponent("/my/posts?tab=1&x=%2F")}`,
      });
    });

    it("보호 경로와 이름만 비슷한 경로(/homepage)는 보호하지 않는다", () => {
      expect(resolveRouteGuardAction("/homepage", SIGNED_OUT)).toBeNull();
    });
  });

  describe("표 3행: 보호 경로 + ogu_rt 있음 + ogu_ob 없음 → /onboarding", () => {
    it.each([...PROTECTED_PATHS, "/settings/profile"])(
      "%s는 온보딩 전이면 /onboarding으로 보낸다",
      (pathname) => {
        expect(resolveRouteGuardAction(pathname, NOT_ONBOARDED)).toEqual({
          type: "redirect",
          to: "/onboarding",
        });
      },
    );

    it.each(PROTECTED_PATHS)(
      "%s는 온보딩을 마쳤으면 그대로 통과한다",
      (pathname) => {
        expect(resolveRouteGuardAction(pathname, ONBOARDED)).toBeNull();
      },
    );
  });

  describe("표 4행: /onboarding + ogu_rt 없음 → /login", () => {
    it("로그아웃 상태면 /onboarding에서 /login으로 보낸다", () => {
      expect(resolveRouteGuardAction("/onboarding", SIGNED_OUT)).toEqual({
        type: "redirect",
        to: "/login",
      });
    });
  });

  describe("표 5행: /onboarding + ogu_ob 있음 → /home", () => {
    it("이미 온보딩했으면 /home으로 보낸다", () => {
      expect(resolveRouteGuardAction("/onboarding", ONBOARDED)).toEqual({
        type: "redirect",
        to: "/home",
      });
    });

    it("검증을 통과한 next가 있으면 그곳으로 보낸다", () => {
      expect(
        resolveRouteGuardAction("/onboarding", ONBOARDED, "?next=%2Fmy"),
      ).toEqual({ type: "redirect", to: "/my" });
    });

    it("아직 온보딩하지 않았으면 /onboarding을 그대로 보여준다", () => {
      expect(resolveRouteGuardAction("/onboarding", NOT_ONBOARDED)).toBeNull();
    });
  });

  describe("표 6행: /login, /signup + ogu_rt와 ogu_ob 모두 있음 → /home", () => {
    it.each(["/login", "/signup"])(
      "%s는 로그인과 온보딩을 마쳤으면 /home으로 보낸다",
      (pathname) => {
        expect(resolveRouteGuardAction(pathname, ONBOARDED)).toEqual({
          type: "redirect",
          to: "/home",
        });
      },
    );

    it("검증을 통과한 next가 있으면 그곳으로 보낸다", () => {
      expect(
        resolveRouteGuardAction("/login", ONBOARDED, "?next=%2Fmy"),
      ).toEqual({ type: "redirect", to: "/my" });
    });

    it.each([
      "?next=%2F%2Fevil.example",
      "?next=https%3A%2F%2Fevil.example",
      "?next=%2F%5Cevil.example",
      "?next=%2F%252F%252Fevil.example",
    ])("외부 주소 next(%s)는 /home으로 바꾼다", (search) => {
      expect(resolveRouteGuardAction("/login", ONBOARDED, search)).toEqual({
        type: "redirect",
        to: "/home",
      });
    });

    it.each([
      ["/login", SIGNED_OUT],
      ["/signup", SIGNED_OUT],
      ["/login", NOT_ONBOARDED],
      ["/signup", NOT_ONBOARDED],
    ] as const)(
      "%s는 로그인과 온보딩을 모두 마치지 않았으면 그대로 보여준다",
      (pathname, cookies) => {
        expect(resolveRouteGuardAction(pathname, cookies)).toBeNull();
      },
    );
  });

  describe("표에 없는 경로", () => {
    it.each(["/terms", "/login/help"])("%s는 그대로 통과한다", (pathname) => {
      expect(resolveRouteGuardAction(pathname, SIGNED_OUT)).toBeNull();
      expect(resolveRouteGuardAction(pathname, ONBOARDED)).toBeNull();
    });
  });
});
