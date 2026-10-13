import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";

import { AppShell } from "./app-shell";

const route = vi.hoisted(() => ({
  pathname: "/home",
  pending: false,
  replace: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  usePathname: () => route.pathname,
  useRouter: () => ({ replace: route.replace }),
}));
vi.mock("next/link", async () => {
  const actual = await vi.importActual<typeof import("next/link")>("next/link");
  return { ...actual, useLinkStatus: () => ({ pending: route.pending }) };
});

beforeEach(() => {
  route.pathname = "/home";
  route.pending = false;
  route.replace.mockReset();
});

describe("AppShell", () => {
  it("GUEST-US1-AC2 익명 홈 메뉴는 같은 로그인 안내를 열고 취소하면 홈에 남는다", async () => {
    const user = userEvent.setup();
    render(
      <AppShell authenticated={false}>
        <h1>둘러보기</h1>
      </AppShell>,
    );
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const trigger = nav.getByRole("link", { name: "고민 쓰기" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "로그인이 필요해요" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(
      within(dialog).getByRole("button", { name: "계속 둘러보기" }),
    ).toHaveFocus();
    await user.click(
      within(dialog).getByRole("button", { name: "계속 둘러보기" }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
    expect(route.replace).not.toHaveBeenCalled();
  });

  it("GUEST-US1-AC3 로그인 확인은 선택한 내부 메뉴를 next로 보존하고 중복 이동하지 않는다", async () => {
    const user = userEvent.setup();
    render(
      <AppShell authenticated={false}>
        <h1>둘러보기</h1>
      </AppShell>,
    );
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    await user.click(nav.getByRole("link", { name: "내 마음 기록" }));
    const confirm = within(screen.getByRole("dialog")).getByRole("button", {
      name: "로그인하기",
    });
    await user.dblClick(confirm);
    expect(route.replace).toHaveBeenCalledExactlyOnceWith("/login?next=%2Fmy");
    expect(confirm).toBeDisabled();
  });

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
    route.pathname = "/signup";
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
    expect(screen.getByRole("link", { name: /오구오구 홈$/ })).toHaveAttribute(
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
    expect(screen.getByRole("link", { name: /오구오구 홈$/ })).toHaveAttribute(
      "href",
      "/home",
    );
  });

  it("DS-US3-AC7 선택된 메뉴는 채운 아이콘과 굵은 글씨, 나머지는 선 아이콘이다", () => {
    route.pathname = "/raid";
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const selected = nav.getByRole("link", { name: "함께 이겨내기" });
    const other = nav.getByRole("link", { name: "마음 이야기" });
    expect(selected.querySelector("svg")).toHaveAttribute(
      "data-icon-weight",
      "fill",
    );
    expect(other.querySelector("svg")).toHaveAttribute(
      "data-icon-weight",
      "regular",
    );
    expect(selected).toHaveClass("text-fg");
    expect(other).toHaveClass("text-fg-subtle");
  });

  it("좁은 화면의 메뉴는 위쪽 모서리가 둥근 막대이고 그림자 없이 테두리로 본문과 나뉜다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const menu = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    expect(menu).toHaveClass("rounded-t-surface", "border-line", "bg-surface");
    expect(menu.className).not.toMatch(/shadow/);
  });

  it("좁은 화면의 고민 쓰기는 주요 동작 색으로 채운 버튼이고 다른 메뉴보다 넓다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const write = nav.getByRole("link", { name: "고민 쓰기" });
    expect(write).toHaveClass("bg-action", "text-on-action", "flex-[1.4]");
    expect(write).not.toHaveClass("text-fg-subtle");
    expect(within(write).getByText("고민 쓰기")).toBeVisible();
    for (const name of ["마음 이야기", "함께 이겨내기", "내 마음 기록"]) {
      const other = nav.getByRole("link", { name });
      expect(other).not.toHaveClass("bg-action");
      expect(other).toHaveClass("flex-1");
    }
  });

  it("고민 쓰기 화면에 있으면 채운 버튼의 아이콘이 채워지고 현재 위치로 알린다", () => {
    route.pathname = "/write";
    render(<AppShell authenticated>{null}</AppShell>);
    const write = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).getByRole("link", { name: "고민 쓰기" });
    expect(write).toHaveAttribute("aria-current", "page");
    expect(write).toHaveClass("bg-action", "text-on-action");
    expect(write.querySelector("svg")).toHaveAttribute(
      "data-icon-weight",
      "fill",
    );
  });

  it("넓은 화면의 메뉴에서는 고민 쓰기도 다른 메뉴와 같은 모양이다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const write = within(
      screen.getByRole("navigation", { name: "주요 메뉴" }),
    ).getByRole("link", { name: "고민 쓰기" });
    expect(write).not.toHaveClass("bg-action");
    expect(write).toHaveClass("text-fg-subtle");
  });

  it("익명에게 잠긴 고민 쓰기의 자물쇠는 채운 버튼 위에서도 보이는 색이다", () => {
    render(<AppShell authenticated={false}>{null}</AppShell>);
    const write = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).getByRole("link", { name: "고민 쓰기" });
    const lock = write.querySelector("[data-navigation-lock]");
    expect(lock).toHaveClass("bg-surface", "text-fg");
  });

  it("GUEST-US1-AC2 잠긴 메뉴는 아이콘이 바뀐 뒤에도 설명으로 잠김을 알린다", () => {
    render(<AppShell authenticated={false}>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    expect(
      nav.getByRole("link", { name: "고민 쓰기" }),
    ).toHaveAccessibleDescription("로그인 후 이용할 수 있어요");
    expect(
      nav.getByRole("link", { name: "마음 이야기" }),
    ).not.toHaveAccessibleDescription();
  });

  it("이동 대기 중에는 아이콘 자리에 돌아가는 표시와 이동 중 안내가 나온다", () => {
    route.pending = true;
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    expect(
      nav.querySelectorAll('[data-navigation-pending="true"]').length,
    ).toBeGreaterThan(0);
    expect(within(nav).getAllByText("이동 중").length).toBeGreaterThan(0);
  });

  it("DS-US4-AC1 좁은 화면의 메뉴는 본문보다 앞에 있어 끝없는 피드를 지나지 않고 닿는다", () => {
    render(
      <AppShell authenticated>
        <h1>마음 이야기</h1>
      </AppShell>,
    );
    const menu = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    const main = screen.getByRole("main");
    expect(
      menu.compareDocumentPosition(main) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("DS-US4-AC1 홈 링크의 이름에는 눈에 보이는 글자가 그대로 들어 있다", () => {
    render(
      <AppShell authenticated>
        <h1>마음 이야기</h1>
      </AppShell>,
    );
    const home = screen.getByRole("link", { name: /오구오구 홈$/ });
    expect(home).toHaveAccessibleName(
      expect.stringContaining(home.textContent ?? ""),
    );
  });
});
