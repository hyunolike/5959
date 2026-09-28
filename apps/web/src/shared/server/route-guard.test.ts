import { describe, expect, it } from "vitest";

import { resolveRouteGuardAction } from "./route-guard";

/**
 * bff-routes.md "라우트 가드(proxy.ts)" 표 중 이 배치(T039, US1 범위)가 구현하는
 * 두 행만 검증한다: `ogu_ob` 없으면 보호 경로에서 `/onboarding`으로, `/onboarding`에
 * `ogu_ob`가 있으면 `/home`으로. 나머지 행(`/` → `/home`, 보호 경로+ogu_rt 없음
 * → `/login?next=`, `/onboarding`+ogu_rt 없음 → `/login`, `/login`·`/signup` →
 * `/home`)은 T063(US4)에서 추가한다 — 이 함수는 아직 그 행들을 모른다.
 */
describe("resolveRouteGuardAction", () => {
  describe("보호 경로 + ogu_rt 있음 + ogu_ob 없음 → /onboarding", () => {
    it.each(["/home", "/write", "/my", "/settings"])(
      "%s는 온보딩 전이면 /onboarding으로 보낸다",
      (pathname) => {
        const action = resolveRouteGuardAction(pathname, {
          hasRefreshToken: true,
          onboarded: false,
        });
        expect(action).toEqual({ type: "redirect", to: "/onboarding" });
      },
    );

    it("보호 경로의 하위 경로(/my/settings)도 /onboarding으로 보낸다", () => {
      const action = resolveRouteGuardAction("/settings/profile", {
        hasRefreshToken: true,
        onboarded: false,
      });
      expect(action).toEqual({ type: "redirect", to: "/onboarding" });
    });
  });

  describe("보호 경로 + ogu_ob 있음 → 통과", () => {
    it.each(["/home", "/write", "/my", "/settings"])(
      "%s는 온보딩을 마쳤으면 그대로 통과한다",
      (pathname) => {
        const action = resolveRouteGuardAction(pathname, {
          hasRefreshToken: true,
          onboarded: true,
        });
        expect(action).toBeNull();
      },
    );
  });

  describe("보호 경로 + ogu_rt 없음(이 배치의 범위 밖) → 통과", () => {
    it("이 배치는 로그인 여부를 판단하지 않는다(T063에서 추가)", () => {
      const action = resolveRouteGuardAction("/home", {
        hasRefreshToken: false,
        onboarded: false,
      });
      expect(action).toBeNull();
    });
  });

  describe("/onboarding + ogu_ob 있음 → /home", () => {
    it("이미 온보딩했으면 /onboarding에서 /home으로 보낸다", () => {
      const action = resolveRouteGuardAction("/onboarding", {
        hasRefreshToken: true,
        onboarded: true,
      });
      expect(action).toEqual({ type: "redirect", to: "/home" });
    });
  });

  describe("/onboarding + ogu_ob 없음 → 통과", () => {
    it("아직 온보딩하지 않았으면 /onboarding을 그대로 보여준다", () => {
      const action = resolveRouteGuardAction("/onboarding", {
        hasRefreshToken: true,
        onboarded: false,
      });
      expect(action).toBeNull();
    });
  });

  describe("이 배치가 다루지 않는 경로는 통과시킨다", () => {
    it.each(["/", "/login", "/signup"])(
      "%s는 이 배치의 규칙과 관계없다",
      (pathname) => {
        const action = resolveRouteGuardAction(pathname, {
          hasRefreshToken: false,
          onboarded: false,
        });
        expect(action).toBeNull();
      },
    );
  });
});
