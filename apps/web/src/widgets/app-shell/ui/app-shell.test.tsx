import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AppShell } from "./app-shell";

const route = vi.hoisted(() => ({ pathname: "/home", pending: false }));
vi.mock("next/navigation", () => ({ usePathname: () => route.pathname }));
vi.mock("next/link", async () => {
  const actual = await vi.importActual<typeof import("next/link")>("next/link");
  return { ...actual, useLinkStatus: () => ({ pending: route.pending }) };
});

beforeEach(() => {
  route.pathname = "/home";
  route.pending = false;
});

describe("AppShell", () => {
  it("이동 대기는 실제 현재 위치와 분리하고 주소가 확정된 뒤에만 선택을 바꾼다", () => {
    route.pending = true;
    const { rerender } = render(
      <AppShell authenticated>
        <h1>피드</h1>
      </AppShell>,
    );
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const target = nav.getByRole("link", { name: "내 마음 기록" });
    expect(nav.getByRole("link", { name: "마음 이야기" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(target).not.toHaveAttribute("aria-current");
    expect(within(target).getByText("이동 중", { exact: true })).toBeVisible();
    expect(target.querySelector('[aria-busy="true"]')).not.toBeNull();
    route.pathname = "/my";
    route.pending = false;
    rerender(
      <AppShell authenticated>
        <h1>내 기록</h1>
      </AppShell>,
    );
    expect(target).toHaveAttribute("aria-current", "page");
    expect(
      within(target).queryByText("이동 중", { exact: true }),
    ).not.toBeInTheDocument();
  });
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
        "location",
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
