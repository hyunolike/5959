import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import HomePage from "./page";
import { AppShell } from "@/widgets/app-shell";

const me = vi.hoisted(() => ({
  state: {} as { data?: { nickname: string }; isError: boolean },
}));

vi.mock("@/entities/member", () => ({
  useMeQuery: () => me.state,
}));
vi.mock("@/features/auth/logout", () => ({
  LogoutButton: () => <button type="button">로그아웃</button>,
}));
vi.mock("@/widgets/raid-arena", () => ({
  BossBanner: () => <a href="/raid">레이드</a>,
}));
vi.mock("@/widgets/feed-list", () => ({
  FeedList: () => <section aria-label="피드" />,
}));
vi.mock("@/features/install-app", () => ({ HomeInstall: () => null }));
vi.mock("next/navigation", () => ({
  usePathname: () => "/home",
  useRouter: () => ({ replace: vi.fn() }),
}));
vi.mock("next/link", async () => {
  const actual = await vi.importActual<typeof import("next/link")>("next/link");
  return { ...actual, useLinkStatus: () => ({ pending: false }) };
});

beforeEach(() => {
  me.state = { isError: false };
});

describe("HomePage", () => {
  it("GUEST-US1-AC4 익명 홈은 회원 글이나 프로필 대신 보호 범위를 안내한다", () => {
    me.state = { data: { nickname: "비공개회원" }, isError: false };
    render(
      <AppShell authenticated={false}>
        <HomePage />
      </AppShell>,
    );
    expect(
      screen.queryByText("비공개회원님, 반가워요"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("region", { name: "피드" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("heading", {
        name: "마음 이야기는 로그인 후 볼 수 있어요",
      }),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole("main")).getByRole("button", {
        name: "고민 쓰기",
      }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "로그아웃" }),
    ).not.toBeInTheDocument();
  });

  it("피드 제목과 닉네임 인사, 글쓰기 버튼을 보여 준다", () => {
    me.state = { data: { nickname: "오구" }, isError: false };

    render(
      <AppShell authenticated>
        <HomePage />
      </AppShell>,
    );
    const main = within(screen.getByRole("main"));

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(
      "마음 이야기",
    );
    expect(screen.getByText("오구님, 반가워요")).toBeInTheDocument();
    expect(main.getByRole("link", { name: "고민 쓰기" })).toHaveAttribute(
      "href",
      "/write",
    );
    expect(screen.getByRole("region", { name: "피드" })).toBeInTheDocument();
    // 006 US5-AC7: 홈에서 레이드로 가는 안내가 있다
    expect(main.getByRole("link", { name: "레이드" })).toHaveAttribute(
      "href",
      "/raid",
    );
  });

  it("프로필을 불러오지 못하면 안내를 보여 준다", () => {
    me.state = { isError: true };

    render(
      <AppShell authenticated>
        <HomePage />
      </AppShell>,
    );

    expect(screen.getByRole("alert")).toHaveTextContent(
      "프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.",
    );
  });
});
