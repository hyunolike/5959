import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AppShell } from "./app-shell";

const route = vi.hoisted(() => ({ pathname: "/home" }));
vi.mock("next/navigation", () => ({ usePathname: () => route.pathname }));

beforeEach(() => {
  route.pathname = "/home";
});

describe("AppShell", () => {
  it("로그인 전에는 가입 화면에서 보호된 메뉴와 알림을 노출하지 않는다", () => {
    render(
      <AppShell
        authenticated={false}
        notification={<a href="/notifications">알림</a>}
      >
        <h1>로그인</h1>
      </AppShell>,
    );
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
    expect(
      screen.queryByRole("link", { name: "알림" }),
    ).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "오구오구 홈" })).toHaveAttribute(
      "href",
      "/login",
    );
    expect(
      screen.getByRole("link", { name: "본문으로 건너뛰기" }),
    ).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");
  });

  it("데스크톱과 모바일에서 같은 메뉴와 현재 위치를 제공한다", () => {
    route.pathname = "/my/edit";
    render(
      <AppShell authenticated notification={<a href="/notifications">알림</a>}>
        <h1>프로필 수정</h1>
      </AppShell>,
    );
    for (const name of ["주요 메뉴", "모바일 주요 메뉴"]) {
      const nav = within(screen.getByRole("navigation", { name }));
      expect(nav.getByRole("link", { name: "내 마음 기록" })).toHaveAttribute(
        "aria-current",
        "page",
      );
      expect(nav.getByRole("link", { name: "함께 이겨내기" })).toHaveAttribute(
        "href",
        "/raid",
      );
      expect(
        nav.getByRole("link", { name: "마음 이야기" }),
      ).not.toHaveAttribute("aria-current");
    }
    expect(screen.getByRole("link", { name: "알림" })).toHaveAttribute(
      "href",
      "/notifications",
    );
    expect(screen.getByRole("link", { name: "오구오구 홈" })).toHaveAttribute(
      "href",
      "/home",
    );
  });
});
