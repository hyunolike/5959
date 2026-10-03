import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Comment } from "../model/types";
import { CommentItem } from "./comment-item";

function comment(overrides: Partial<Comment> = {}): Comment {
  return {
    commentId: 1,
    author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
    content: "힘내요",
    likeCount: 3,
    likedByMe: false,
    mine: false,
    createdAt: "2026-10-03T00:00:00Z",
    replies: [],
    ...overrides,
  };
}

describe("CommentItem", () => {
  it("작성자 닉네임, 본문, 공감 수를 보여 준다", () => {
    render(<CommentItem comment={comment()} />);

    const item = screen.getByRole("article", { name: "공감러의 댓글" });
    expect(within(item).getByText("공감러")).toBeInTheDocument();
    expect(within(item).getByText("힘내요")).toBeInTheDocument();
    expect(within(item).getByText("공감 3")).toBeInTheDocument();
  });

  it("US3-AC3 답글은 원 댓글 아래에 들여 써서 보여 준다", () => {
    render(
      <CommentItem
        comment={comment({
          replies: [
            comment({
              commentId: 2,
              author: {
                id: 3,
                nickname: "답글러",
                jobRole: "SALES",
                careerYear: "YEAR_1",
              },
              content: "저도요",
              likeCount: 1,
            }),
          ],
        })}
      />,
    );

    const replies = screen.getByRole("list", { name: "답글 목록" });
    expect(replies).toHaveClass("pl-6");
    const reply = within(replies).getByRole("article", {
      name: "답글러의 답글",
    });
    expect(within(reply).getByText("저도요")).toBeInTheDocument();
    expect(within(reply).getByText("공감 1")).toBeInTheDocument();
  });

  it("행동 버튼 자리는 원 댓글과 답글에 각각 그린다", () => {
    render(
      <CommentItem
        comment={comment({ replies: [comment({ commentId: 2 })] })}
        renderActions={(target, { isReply }) => (
          <button type="button">
            {isReply ? "답글" : "원 댓글"} {target.commentId} 행동
          </button>
        )}
      />,
    );

    expect(
      screen.getByRole("button", { name: "원 댓글 1 행동" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "답글 2 행동" }),
    ).toBeInTheDocument();
  });

  it("행동 버튼 자리를 넘기면 공감 수는 그 자리가 보여 준다고 보고 따로 쓰지 않는다", () => {
    render(
      <CommentItem
        comment={comment()}
        renderActions={() => <span>공감 버튼</span>}
      />,
    );
    expect(screen.queryByText("공감 3")).not.toBeInTheDocument();
  });
});
