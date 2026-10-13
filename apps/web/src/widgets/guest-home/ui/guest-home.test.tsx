import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import type { PublicFeedItem } from "@/entities/post";

const requestLogin = vi.hoisted(() => vi.fn());
vi.mock("@/features/auth/guest-access", () => ({
  useGuestAccess: () => ({ authenticated: false, requestLogin }),
}));

const feed = vi.hoisted(() => ({
  state: {} as {
    data?: { items: unknown[] };
    isPending: boolean;
    isError: boolean;
    isFetching: boolean;
    refetch: () => void;
  },
}));
vi.mock("@/entities/post", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/entities/post")>()),
  usePublicFeedQuery: () => feed.state,
}));

import { GuestHome } from "./guest-home";

function item(overrides: Partial<PublicFeedItem> = {}): PublicFeedItem {
  return {
    postId: 7,
    jobRole: "DEVELOPMENT",
    careerYear: "YEAR_3",
    ai: false,
    contentPreview: "내일 발표가 걱정돼요",
    analysisStatus: "ANALYZED",
    monster: { emotion: "ANXIETY", hp: 2, maxHp: 10, status: "ALIVE" },
    likeCount: 3,
    commentCount: 2,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

function loaded(items: PublicFeedItem[]) {
  feed.state = {
    data: { items },
    isPending: false,
    isError: false,
    isFetching: false,
    refetch: vi.fn(),
  };
}

const stories = () => screen.getByRole("region", { name: "최근 이야기" });

beforeEach(() => {
  requestLogin.mockReset();
  loaded([]);
});

describe("GuestHome", () => {
  it("DS-US3-AC5 다섯 감정의 몬스터 그림과 이름을 보인다", () => {
    render(<GuestHome />);
    expect(
      screen.getAllByRole("img", { name: /몬스터, 멀쩡함$/ }),
    ).toHaveLength(5);
    for (const name of ["불안", "무기력", "외로움", "자기비하", "짜증"]) {
      expect(screen.getByText(name, { exact: true })).toBeInTheDocument();
    }
  });

  it("US1-AC1 받은 순서대로 글을 목록으로 보인다", () => {
    loaded([
      item({ postId: 9, contentPreview: "가장 최근 글" }),
      item({ postId: 8, contentPreview: "그 앞의 글" }),
    ]);
    render(<GuestHome />);
    const rows = within(stories()).getAllByRole("listitem");
    expect(rows).toHaveLength(2);
    expect(rows[0]).toHaveTextContent("가장 최근 글");
    expect(rows[1]).toHaveTextContent("그 앞의 글");
  });

  it("US1-AC2 감정, 직군과 경력, 본문, 몬스터와 HP, 공감 수와 댓글 수가 보인다", () => {
    loaded([item()]);
    render(<GuestHome />);
    const row = within(stories()).getByRole("listitem");
    expect(within(row).getByText("불안")).toBeInTheDocument();
    expect(within(row).getByText("개발 · 3년차")).toBeInTheDocument();
    expect(within(row).getByText("내일 발표가 걱정돼요")).toBeInTheDocument();
    expect(within(row).getByText("HP 2/10")).toBeInTheDocument();
    expect(within(row).getByRole("progressbar")).toBeInTheDocument();
    expect(within(row).getByText("공감 3")).toBeInTheDocument();
    expect(within(row).getByText("댓글 2")).toBeInTheDocument();
  });

  it("012 US4-AC3 AI 친구의 글은 직군과 경력 자리에 AI 친구라고 보이고 닉네임은 없다", () => {
    loaded([
      item({
        postId: 9,
        jobRole: null,
        careerYear: null,
        ai: true,
        contentPreview: "회의에서 한마디도 못 했어요",
      }),
      item({ postId: 8, contentPreview: "사람이 쓴 글" }),
    ]);
    render(<GuestHome />);

    const rows = within(stories()).getAllByRole("listitem");
    expect(rows[0]).toHaveTextContent("AI 친구");
    expect(rows[0]).not.toHaveTextContent("년차");
    expect(rows[0]).not.toHaveTextContent("오구");
    // 카드 전체가 로그인 안내를 여는 버튼이라 표시는 링크가 아니라 글자다
    expect(within(rows[0]).queryByRole("link")).not.toBeInTheDocument();
    expect(rows[1]).toHaveTextContent("개발 · 3년차");
    expect(rows[1]).not.toHaveTextContent("AI 친구");
  });

  it("US1-AC3 닉네임 자리, 글 상세 링크, 회원의 피드 구역이 없다", () => {
    loaded([item()]);
    const { container } = render(<GuestHome />);
    expect(
      screen.queryByRole("region", { name: "피드" }),
    ).not.toBeInTheDocument();
    expect(container.querySelector('a[href^="/post/"]')).toBeNull();
  });

  it("US1-AC4 글을 누르면 로그인 안내가 그 글로 열린다", async () => {
    const user = userEvent.setup();
    loaded([item({ postId: 42 })]);
    render(<GuestHome />);
    await user.click(
      within(stories()).getByRole("button", { name: "로그인하고 이 글 읽기" }),
    );
    expect(requestLogin).toHaveBeenLastCalledWith("/post/42");
  });

  it("US1-AC5 스무 개가 찼을 때만 더 보려면 로그인하라고 알린다", () => {
    loaded(Array.from({ length: 20 }, (_, index) => item({ postId: index })));
    const { unmount } = render(<GuestHome />);
    expect(
      within(stories()).getByText(/더 많은 이야기는 로그인하면 볼 수 있어요/),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    ).toBeInTheDocument();
    unmount();

    loaded(Array.from({ length: 19 }, (_, index) => item({ postId: index })));
    render(<GuestHome />);
    expect(screen.queryByText(/더 많은 이야기는/)).not.toBeInTheDocument();
  });

  it("US1-AC6 보일 글이 없으면 아직 이야기가 없다고 알린다", () => {
    render(<GuestHome />);
    expect(
      within(stories()).getByText("아직 올라온 이야기가 없어요."),
    ).toBeInTheDocument();
    expect(within(stories()).queryByRole("list")).not.toBeInTheDocument();
  });

  it("US1-AC7 불러오지 못해도 소개와 로그인으로 가는 길은 남는다", async () => {
    const user = userEvent.setup();
    const refetch = vi.fn();
    feed.state = {
      isPending: false,
      isError: true,
      isFetching: false,
      refetch,
    };
    render(<GuestHome />);
    expect(within(stories()).getByRole("alert")).toHaveTextContent(
      "이야기를 불러오지 못했어요",
    );
    await user.click(
      within(stories()).getByRole("button", { name: "다시 불러오기" }),
    );
    expect(refetch).toHaveBeenCalledOnce();
    expect(
      screen.getByRole("heading", { level: 1, name: "마음 이야기" }),
    ).toBeInTheDocument();
    expect(screen.getByText("오구오구는 이렇게 써요")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
      "href",
      "/signup",
    );
  });

  it("US1-AC1 불러오는 동안에는 불러오는 중이라고 알린다", () => {
    feed.state = {
      isPending: true,
      isError: false,
      isFetching: true,
      refetch: vi.fn(),
    };
    render(<GuestHome />);
    expect(within(stories()).getByRole("status")).toHaveTextContent(
      "최근 이야기 불러오는 중",
    );
  });

  it("US3-AC2 글을 볼 수 없다는 옛 문구 대신 바뀐 공개 범위를 말한다", () => {
    const { container } = render(<GuestHome />);
    expect(container).not.toHaveTextContent("로그인 후 볼 수 있어요");
    expect(container).not.toHaveTextContent("로그인한 분께만");
    expect(stories()).toHaveTextContent(
      "글의 앞부분을 닉네임 없이 보여드려요. 글 전체와 댓글은 로그인하면 볼 수 있어요.",
    );
  });

  it("GUEST-US1-AC2 버튼은 가려던 곳과 함께 로그인 안내를 연다", async () => {
    const user = userEvent.setup();
    render(<GuestHome />);
    await user.click(
      screen.getByRole("button", { name: "로그인하고 이야기 보기" }),
    );
    expect(requestLogin).toHaveBeenLastCalledWith("/home");
    await user.click(screen.getByRole("button", { name: "고민 쓰기" }));
    expect(requestLogin).toHaveBeenLastCalledWith("/write");
  });

  it("DS-US3-AC5 메뉴 설명 목록은 두지 않는다", () => {
    render(<GuestHome />);
    expect(screen.queryByText("메뉴 안내")).not.toBeInTheDocument();
  });
});
