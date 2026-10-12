import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { FeedItem } from "../model/types";
import { PostCard } from "./post-card";

function item(overrides: Partial<FeedItem> = {}): FeedItem {
  return {
    postId: 7,
    author: {
      id: 1,
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
    },
    contentPreview: "내일 발표가 걱정돼요",
    analysisStatus: "ANALYZED",
    monster: { emotion: "ANXIETY", hp: 7, maxHp: 10, status: "ALIVE" },
    hidden: false,
    likeCount: 3,
    likedByMe: true,
    commentCount: 2,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

describe("PostCard", () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-03T00:12:00Z"));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("US2-AC1 작성자, 미리보기, 몬스터, 공감 수, 댓글 수, 내 공감 여부를 보여 주고 상세로 이어진다", () => {
    render(
      <PostCard
        item={item()}
        authorMeta="개발 · 1년차"
        renderEmotion={(monster) => <p>감정 {monster.emotion}</p>}
        renderMonster={(monster) => <p>몬스터 {monster.emotion}</p>}
      />,
    );

    expect(screen.getByText("오구")).toBeInTheDocument();
    expect(screen.getByText("개발 · 1년차")).toBeInTheDocument();
    expect(screen.getByText("내일 발표가 걱정돼요")).toBeInTheDocument();
    expect(screen.getByText("몬스터 ANXIETY")).toBeInTheDocument();
    expect(screen.getByText("감정 ANXIETY")).toBeInTheDocument();
    expect(screen.getByText("공감 3")).toBeInTheDocument();
    expect(screen.getByText("댓글 2")).toBeInTheDocument();
    expect(screen.getByText("내가 공감함")).toBeInTheDocument();
    expect(screen.getByRole("link")).toHaveAttribute("href", "/post/7");
  });

  it("몬스터가 아직 없으면 분석 중을 보여 준다", () => {
    render(
      <PostCard
        item={item({
          monster: null,
          analysisStatus: "PENDING",
          likedByMe: false,
        })}
        authorMeta="개발 · 1년차"
        renderEmotion={() => <p>감정</p>}
        renderMonster={() => <p>몬스터</p>}
      />,
    );

    expect(screen.getByText("분석 중")).toBeInTheDocument();
    expect(screen.queryByText("몬스터")).not.toBeInTheDocument();
    expect(screen.queryByText("감정")).not.toBeInTheDocument();
    expect(screen.queryByText("내가 공감함")).not.toBeInTheDocument();
  });

  it("DS-US3-AC1 작성 시각을 상대 시각으로 쓰고 원래 시각은 datetime에 둔다", () => {
    render(
      <PostCard
        item={item()}
        authorMeta="개발 · 1년차"
        showCreatedAt
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    const time = screen.getByText("12분 전");
    expect(time.tagName).toBe("TIME");
    expect(time).toHaveAttribute("datetime", "2026-10-03T00:00:00Z");
  });

  it("작성 시각이 깨진 값이면 빈 칸으로 두고 datetime은 그대로 둔다", () => {
    const { container } = render(
      <PostCard
        item={item({ createdAt: "not-a-date" })}
        authorMeta="개발 · 1년차"
        showCreatedAt
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    const time = container.querySelector("time");
    expect(time).toHaveTextContent("");
    expect(time).toHaveAttribute("datetime", "not-a-date");
    expect(container).not.toHaveTextContent("Invalid");
  });

  it("DS-US3-AC4 숨겨진 글 표시는 감정 색이 아니라 주의 색을 쓴다", () => {
    render(
      <PostCard
        item={item({ hidden: true })}
        authorMeta="개발 · 1년차"
        renderEmotion={() => null}
        renderMonster={() => null}
      />,
    );
    expect(screen.getByText("다른 회원에게 보이지 않아요")).toHaveClass(
      "bg-warning-tint",
      "text-warning",
    );
  });
});
