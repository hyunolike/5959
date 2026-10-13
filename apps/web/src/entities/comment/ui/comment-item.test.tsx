import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { Comment } from "../model/types";
import { CommentItem } from "./comment-item";

function comment(overrides: Partial<Comment> = {}): Comment {
  return {
    commentId: 1,
    author: {
      id: 2,
      nickname: "공감러",
      jobRole: "HR",
      careerYear: "YEAR_2",
      ai: false,
    },
    content: "힘내요",
    hidden: false,
    likeCount: 3,
    likedByMe: false,
    mine: false,
    createdAt: "2026-10-03T00:00:00Z",
    replies: [],
    ...overrides,
  };
}

describe("CommentItem", () => {
  const AI_AUTHOR = {
    id: 9,
    nickname: "다정한 오구",
    jobRole: null,
    careerYear: null,
    ai: true,
  };

  it("US2-AC1 AI 친구의 댓글은 닉네임 옆에 AI 친구라고 글자로 보인다", () => {
    render(<CommentItem comment={comment({ author: AI_AUTHOR })} />);

    const item = screen.getByRole("article", {
      name: "AI 친구 다정한 오구의 댓글",
    });
    expect(within(item).getByText("다정한 오구")).toBeInTheDocument();
    expect(within(item).getByRole("link", { name: "AI 친구" })).toHaveAttribute(
      "href",
      "/ai-friends",
    );
  });

  it("US2-AC1 AI 친구의 답글도 이름에 AI 친구가 들어 있다", () => {
    render(
      <CommentItem
        comment={comment({
          replies: [comment({ commentId: 2, author: AI_AUTHOR })],
        })}
      />,
    );

    expect(
      screen.getByRole("article", { name: "AI 친구 다정한 오구의 답글" }),
    ).toBeInTheDocument();
  });

  it("US2-AC3 AI 친구의 댓글에는 직군과 경력을 꾸며 보이지 않는다", () => {
    render(<CommentItem comment={comment({ author: AI_AUTHOR })} />);

    const item = screen.getByRole("article", {
      name: "AI 친구 다정한 오구의 댓글",
    });
    expect(item).not.toHaveTextContent(/년차|신입|개발|인사|기획/);
  });

  it("US2-AC1 사람의 댓글에는 AI 표시가 없다", () => {
    render(<CommentItem comment={comment()} />);

    expect(
      screen.queryByRole("link", { name: "AI 친구" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("article", { name: "공감러의 댓글" }),
    ).toBeInTheDocument();
  });

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
                ai: false,
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

  it('US1-AC5 숨긴 댓글을 다른 회원이 보면 "가려진 댓글이에요"만 보이고 답글은 그대로 보인다', () => {
    render(
      <CommentItem
        comment={comment({
          author: null,
          content: null,
          hidden: true,
          replies: [
            comment({
              commentId: 2,
              author: {
                id: 3,
                nickname: "답글러",
                jobRole: "SALES",
                careerYear: "YEAR_1",
                ai: false,
              },
              content: "힘내세요",
            }),
          ],
        })}
        renderActions={() => <button type="button">공감</button>}
      />,
    );

    const hidden = screen.getByRole("article", { name: "가려진 댓글" });
    expect(hidden).toHaveTextContent("가려진 댓글이에요");
    // 가려진 댓글에는 공감이나 답글 버튼을 두지 않는다
    expect(within(hidden).queryByRole("button")).not.toBeInTheDocument();
    const reply = screen.getByRole("article", { name: "답글러의 답글" });
    expect(within(reply).getByText("힘내세요")).toBeInTheDocument();
    expect(within(reply).getByRole("button", { name: "공감" })).toBeVisible();
  });

  it("내 댓글이 숨겨졌으면 내용과 함께 다른 회원에게 보이지 않는다는 표시가 보인다", () => {
    render(
      <CommentItem
        comment={comment({
          mine: true,
          hidden: true,
          safety: { level: "CRISIS", hidden: true, reviewRequested: false },
        })}
      />,
    );

    const item = screen.getByRole("article", { name: "공감러의 댓글" });
    expect(within(item).getByText("힘내요")).toBeInTheDocument();
    expect(
      within(item).getByText("다른 회원에게 보이지 않아요"),
    ).toBeInTheDocument();
  });

  it("DS-US3-AC4 숨겨진 내 댓글 표시는 감정 색이 아니라 주의 색이다", () => {
    render(<CommentItem comment={comment({ hidden: true, mine: true })} />);
    expect(screen.getByText("다른 회원에게 보이지 않아요")).toHaveClass(
      "bg-warning-tint",
      "text-warning",
    );
  });
});
