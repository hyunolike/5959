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
    // 글자로 "이동 중"을 띄우지 않는다. 아이콘 자리의 돌아가는 표시와 낭독용 안내만 둔다
    expect(
      within(target).queryByText("이동 중", { exact: true }),
    ).not.toBeInTheDocument();
    expect(target.querySelector('[aria-busy="true"]')).not.toBeNull();
    expect(within(target).getByText("내 마음 기록 이동 중")).toHaveClass(
      "sr-only",
    );
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
    // 로그인 전의 로고는 서비스 소개(랜딩)로 간다.
    expect(screen.getByRole("link", { name: /오구오구 홈$/ })).toHaveAttribute(
      "href",
      "/",
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

  it("POLISH-AC12 글 상세에서는 좁은 화면의 하단 메뉴가 없고 옆 메뉴는 있다", () => {
    route.pathname = "/post/42";
    render(<AppShell authenticated>{null}</AppShell>);
    expect(
      screen.queryByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "주요 메뉴" }),
    ).toBeInTheDocument();
  });

  it("POLISH-AC12 글 고치기에서는 하단 메뉴가 있다", () => {
    route.pathname = "/post/42/edit";
    render(<AppShell authenticated>{null}</AppShell>);
    expect(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).toBeInTheDocument();
  });

  it("POLISH-AC17 본문 아래의 여백은 하단 메뉴와 댓글 입력줄 가운데 높은 쪽을 따른다", () => {
    route.pathname = "/post/42";
    render(<AppShell authenticated>{null}</AppShell>);
    const padded = screen.getByRole("main").parentElement!;
    expect(padded.className).toContain("var(--comment-dock-height,0px)");
    expect(padded.className).toContain("104px");
  });

  it("POLISH-AC12 좁은 화면의 머리말은 56px이다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    expect(
      screen.getByRole("link", { name: "오구오구 홈" }).parentElement,
    ).toHaveClass("h-14", "sm:h-18");
  });

  it("좁은 화면의 메뉴는 위쪽 모서리가 둥근 막대이고 그림자 없이 테두리로 본문과 나뉜다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const menu = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    expect(menu).toHaveClass("rounded-t-surface", "border-line", "bg-surface");
    expect(menu.className).not.toMatch(/shadow/);
  });

  it("US5-AC1 좁은 화면의 메뉴는 이야기, 함께, 고민 쓰기, 도감, 기록이고 고민 쓰기가 한가운데다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const menu = screen.getByRole("navigation", { name: "모바일 주요 메뉴" });
    // 다섯 칸이라 셋째 칸이 정가운데다. 두 칸을 쓰는 메뉴는 없다.
    expect(menu).toHaveClass("grid", "grid-cols-5");
    const links = within(menu).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("aria-label"))).toEqual([
      "마음 이야기",
      "함께 이겨내기",
      "고민 쓰기",
      "몬스터 도감",
      "내 마음 기록",
    ]);
    expect(links.map((link) => link.textContent)).toEqual([
      "이야기",
      "함께",
      "고민 쓰기",
      "도감",
      "기록",
    ]);
    for (const link of links) {
      expect(link).not.toHaveClass("col-span-2");
    }
    expect(links[3]).toHaveAttribute("href", "/cards");
  });

  it("US5-AC2 넓은 화면의 메뉴에도 같은 순서로 도감이 있다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const links = within(
      screen.getByRole("navigation", { name: "주요 메뉴" }),
    ).getAllByRole("link");
    expect(links.map((link) => link.getAttribute("aria-label"))).toEqual([
      "마음 이야기",
      "함께 이겨내기",
      "고민 쓰기",
      "몬스터 도감",
      "내 마음 기록",
    ]);
    expect(links[3]).toHaveTextContent("몬스터 도감");
  });

  it("US5-AC3 도감 화면에서는 도감이 지금 위치다", () => {
    route.pathname = "/cards";
    render(<AppShell authenticated>{null}</AppShell>);
    for (const name of ["주요 메뉴", "모바일 주요 메뉴"]) {
      const nav = within(screen.getByRole("navigation", { name }));
      expect(nav.getByRole("link", { name: "몬스터 도감" })).toHaveAttribute(
        "aria-current",
        "page",
      );
      expect(
        nav.getByRole("link", { name: "내 마음 기록" }),
      ).not.toHaveAttribute("aria-current");
    }
  });

  it("US5-AC4 익명에게 도감은 잠겨 있고 누르면 로그인 안내가 열린다", async () => {
    const user = userEvent.setup();
    render(<AppShell authenticated={false}>{null}</AppShell>);
    const cards = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).getByRole("link", { name: "몬스터 도감" });
    expect(cards).toHaveAccessibleDescription("로그인 후 이용할 수 있어요");

    await user.click(cards);
    const confirm = within(
      screen.getByRole("dialog", { name: "로그인이 필요해요" }),
    ).getByRole("button", { name: "로그인하기" });
    await user.click(confirm);
    expect(route.replace).toHaveBeenCalledExactlyOnceWith(
      "/login?next=%2Fcards",
    );
  });

  it("좁은 화면의 고민 쓰기는 막대 위로 올라온 둥근 버튼이고 주요 동작 색이다", () => {
    render(<AppShell authenticated>{null}</AppShell>);
    const nav = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    );
    const write = nav.getByRole("link", { name: "고민 쓰기" });
    const circle = write.querySelector("[data-navigation-primary]");
    expect(circle).toHaveClass(
      "size-14",
      "-mt-6",
      "rounded-full",
      "bg-action",
      "text-on-action",
    );
    // 그림자 대신 면 색 테두리로 본문과 나눈다.
    expect(circle).toHaveClass("border-4", "border-surface");
    expect(circle?.className).not.toMatch(/shadow/);
    expect(within(write).getByText("고민 쓰기")).toBeVisible();
    expect(write).toHaveClass("text-fg");
    for (const name of [
      "마음 이야기",
      "함께 이겨내기",
      "몬스터 도감",
      "내 마음 기록",
    ]) {
      expect(
        nav
          .getByRole("link", { name })
          .querySelector("[data-navigation-primary]"),
      ).toBeNull();
    }
  });

  it("고민 쓰기 화면에 있으면 둥근 버튼의 아이콘이 채워지고 현재 위치로 알린다", () => {
    route.pathname = "/write";
    render(<AppShell authenticated>{null}</AppShell>);
    const write = within(
      screen.getByRole("navigation", { name: "모바일 주요 메뉴" }),
    ).getByRole("link", { name: "고민 쓰기" });
    expect(write).toHaveAttribute("aria-current", "page");
    expect(write.querySelector("[data-navigation-primary]")).toHaveClass(
      "bg-action",
    );
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
    expect(write.querySelector("[data-navigation-primary]")).toBeNull();
    expect(write).toHaveClass("text-fg-subtle");
    expect(write).not.toHaveClass("col-span-2");
  });

  it("익명에게 잠긴 고민 쓰기의 자물쇠는 둥근 버튼 위에서도 보이는 색이다", () => {
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

  it("이동 대기 중에는 아이콘 자리에 돌아가는 표시만 나오고 이동 중이라는 글자는 보이지 않는다", () => {
    route.pending = true;
    render(<AppShell authenticated>{null}</AppShell>);
    for (const name of ["모바일 주요 메뉴", "주요 메뉴"]) {
      const nav = screen.getByRole("navigation", { name });
      expect(
        nav.querySelectorAll('[data-navigation-pending="true"]').length,
      ).toBeGreaterThan(0);
      expect(
        nav.querySelectorAll(".animate-spin, [class*='animate-spin']").length,
      ).toBeGreaterThan(0);
      // 보이는 글자로는 띄우지 않는다. 화면 낭독기에는 어느 메뉴로 가는 중인지 알린다
      expect(
        within(nav).queryByText("이동 중", { exact: true }),
      ).not.toBeInTheDocument();
      for (const announcement of within(nav).getAllByText(/ 이동 중$/)) {
        expect(announcement).toHaveClass("sr-only");
      }
    }
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
