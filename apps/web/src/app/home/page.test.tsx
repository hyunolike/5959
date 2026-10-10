import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import HomePage from "./page";

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

beforeEach(() => {
  me.state = { isError: false };
});

describe("HomePage", () => {
  it("닉네임 인사와 글쓰기 버튼, 피드를 보여 준다", () => {
    me.state = { data: { nickname: "오구" }, isError: false };

    render(<HomePage />);

    expect(screen.getByRole("heading")).toHaveTextContent("오구님, 반가워요");
    expect(screen.getByRole("link", { name: "고민 쓰기" })).toHaveAttribute(
      "href",
      "/write",
    );
    expect(screen.getByRole("region", { name: "피드" })).toBeInTheDocument();
    // 006 US5-AC7: 홈에서 레이드로 가는 안내가 있다
    expect(screen.getByRole("link", { name: "레이드" })).toHaveAttribute(
      "href",
      "/raid",
    );
  });

  it("프로필을 불러오지 못하면 안내를 보여 준다", () => {
    me.state = { isError: true };

    render(<HomePage />);

    expect(screen.getByRole("alert")).toHaveTextContent(
      "프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.",
    );
  });
});
