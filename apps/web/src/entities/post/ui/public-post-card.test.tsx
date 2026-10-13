import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { PublicFeedItem } from "../model/types";
import { PublicPostCard } from "./public-post-card";

function item(overrides: Partial<PublicFeedItem> = {}): PublicFeedItem {
  return {
    postId: 7,
    jobRole: "DEVELOPMENT",
    careerYear: "YEAR_1",
    contentPreview: "내일 발표가 걱정돼요",
    analysisStatus: "ANALYZED",
    monster: { emotion: "ANXIETY", hp: 7, maxHp: 10, status: "ALIVE" },
    likeCount: 3,
    commentCount: 2,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

describe("PublicPostCard", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T00:12:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("US1-AC2 감정, 직군과 경력, 본문, 몬스터, 공감 수와 댓글 수, 올린 때를 보인다", () => {
    render(
      <PublicPostCard
        item={item()}
        authorMeta="개발 · 1년차"
        renderEmotion={(monster) => <span>감정 {monster.emotion}</span>}
        renderMonster={(monster) => <span>몬스터 {monster.emotion}</span>}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText("감정 ANXIETY")).toBeInTheDocument();
    expect(screen.getByText("개발 · 1년차")).toBeInTheDocument();
    expect(screen.getByText("내일 발표가 걱정돼요")).toBeInTheDocument();
    expect(screen.getByText("몬스터 ANXIETY")).toBeInTheDocument();
    expect(screen.getByText("공감 3")).toBeInTheDocument();
    expect(screen.getByText("댓글 2")).toBeInTheDocument();
    const time = screen.getByText("12분 전");
    expect(time).toHaveAttribute("datetime", "2026-10-03T00:00:00Z");
  });

  it("US1-AC2 감정 분석이 끝나지 않은 글은 분석 중이라고 보인다", () => {
    render(
      <PublicPostCard
        item={item({ monster: null, analysisStatus: "PENDING" })}
        authorMeta="개발 · 1년차"
        renderEmotion={() => <span>감정</span>}
        renderMonster={() => <span>몬스터</span>}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText("분석 중")).toBeInTheDocument();
    expect(screen.queryByText("감정")).not.toBeInTheDocument();
    expect(screen.queryByText("몬스터")).not.toBeInTheDocument();
  });

  it("US1-AC3 닉네임 자리와 글 상세로 가는 링크가 없다", () => {
    const { container } = render(
      <PublicPostCard
        item={item()}
        authorMeta="개발 · 1년차"
        renderEmotion={() => null}
        renderMonster={() => null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(container.querySelector('a[href^="/post/"]')).toBeNull();
    expect(container).not.toHaveTextContent("내가 공감함");
  });

  it("US1-AC4 누르면 그 글의 번호로 onSelect를 부른다", async () => {
    vi.useRealTimers();
    const onSelect = vi.fn();
    const user = userEvent.setup();
    render(
      <PublicPostCard
        item={item({ postId: 42 })}
        authorMeta="개발 · 1년차"
        renderEmotion={() => null}
        renderMonster={() => null}
        onSelect={onSelect}
      />,
    );

    const button = screen.getByRole("button", {
      name: "로그인하고 이 글 읽기",
    });
    expect(button).toHaveAccessibleDescription("내일 발표가 걱정돼요");
    await user.click(button);
    expect(onSelect).toHaveBeenCalledExactlyOnceWith(42);
  });
});
