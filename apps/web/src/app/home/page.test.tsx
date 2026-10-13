import { render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import HomePage from "./page";
import { AppShell } from "@/widgets/app-shell";

const me = vi.hoisted(() => ({
  state: {} as { data?: { nickname: string }; isError: boolean },
}));

vi.mock("@/entities/member", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/entities/member")>()),
  useMeQuery: () => me.state,
}));
vi.mock("@/entities/post", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/entities/post")>()),
  usePublicFeedQuery: () => ({
    data: {
      items: [
        {
          postId: 7,
          jobRole: "DEVELOPMENT",
          careerYear: "YEAR_3",
          contentPreview: "내일 발표가 걱정돼요",
          analysisStatus: "ANALYZED",
          monster: { emotion: "ANXIETY", hp: 2, maxHp: 10, status: "ALIVE" },
          likeCount: 3,
          commentCount: 2,
          createdAt: "2026-10-03T00:00:00Z",
        },
      ],
    },
    isPending: false,
    isError: false,
    isFetching: false,
    refetch: vi.fn(),
  }),
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
  it("GUEST-US1-AC4 익명 홈은 프로필과 회원의 피드 없이 닉네임을 가린 최근 글을 보인다", () => {
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
    // 011 US1-AC1: 로그인하지 않아도 최근 이야기가 보인다
    expect(
      within(screen.getByRole("region", { name: "최근 이야기" })).getByText(
        "내일 발표가 걱정돼요",
      ),
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
