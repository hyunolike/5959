import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

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
    likeCount: 3,
    likedByMe: true,
    commentCount: 2,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

describe("PostCard", () => {
  it("US2-AC1 작성자, 미리보기, 몬스터, 공감 수, 댓글 수, 내 공감 여부를 보여 주고 상세로 이어진다", () => {
    render(
      <PostCard
        item={item()}
        authorMeta="개발 · 1년차"
        renderMonster={(monster) => <p>몬스터 {monster.emotion}</p>}
      />,
    );

    expect(screen.getByText("오구")).toBeInTheDocument();
    expect(screen.getByText("개발 · 1년차")).toBeInTheDocument();
    expect(screen.getByText("내일 발표가 걱정돼요")).toBeInTheDocument();
    expect(screen.getByText("몬스터 ANXIETY")).toBeInTheDocument();
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
        renderMonster={() => <p>몬스터</p>}
      />,
    );

    expect(screen.getByText("분석 중")).toBeInTheDocument();
    expect(screen.queryByText("몬스터")).not.toBeInTheDocument();
    expect(screen.queryByText("내가 공감함")).not.toBeInTheDocument();
  });
});
