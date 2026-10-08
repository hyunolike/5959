import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { MyComment } from "../model/types";
import { MyCommentItem } from "./my-comment-item";

const comment = (overrides: Partial<MyComment> = {}): MyComment => ({
  commentId: 7,
  postId: 10,
  postContentPreview: "이직 면접이 걱정된다",
  content: "잘 될 거예요",
  reply: false,
  createdAt: "2026-10-06T03:04:00Z",
  ...overrides,
});

describe("MyCommentItem", () => {
  it("US3-AC2 댓글 본문, 달린 글의 앞부분, 시각을 보이고 누르면 그 글로 간다", () => {
    render(<MyCommentItem comment={comment()} />);

    const link = screen.getByRole("link");
    expect(link).toHaveAttribute("href", "/post/10");
    expect(link).toHaveTextContent("잘 될 거예요");
    expect(link).toHaveTextContent("이직 면접이 걱정된다");
    expect(link.querySelector("time")).toHaveAttribute(
      "datetime",
      "2026-10-06T03:04:00Z",
    );
    expect(screen.queryByText("답글")).not.toBeInTheDocument();
  });

  it("답글이면 답글이라고 글자로 알린다", () => {
    render(<MyCommentItem comment={comment({ reply: true })} />);

    expect(screen.getByText("답글")).toBeInTheDocument();
  });
});
