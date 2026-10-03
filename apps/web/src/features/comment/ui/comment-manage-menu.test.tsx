import {
  QueryClient,
  QueryClientProvider,
  type QueryKey,
} from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { CommentManageMenu } from "./comment-manage-menu";

const COMMENT = {
  commentId: 11,
  author: { id: 2, nickname: "나", jobRole: "HR", careerYear: "YEAR_2" },
  content: "원래 댓글",
  likeCount: 0,
  likedByMe: false,
  mine: true,
  createdAt: "2026-10-03T00:00:00Z",
  replies: [],
} as const;

function renderMenu(response: Response = new Response(null, { status: 204 })) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal("fetch", fetchMock);
  const confirmSpy = vi.fn();
  vi.stubGlobal("confirm", confirmSpy);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const keys: QueryKey[] = [
    ["posts", 7],
    ["posts", 7, "comments"],
  ];
  keys.forEach((key) => queryClient.setQueryData(key, {}));
  render(
    <QueryClientProvider client={queryClient}>
      <CommentManageMenu postId={7} comment={{ ...COMMENT, replies: [] }} />
    </QueryClientProvider>,
  );
  const invalidated = (key: QueryKey) =>
    queryClient.getQueryState(key)?.isInvalidated;
  return { fetchMock, confirmSpy, invalidated };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("CommentManageMenu", () => {
  it("US4-AC3 댓글을 고치면 PATCH를 보내고 댓글 목록과 상세를 다시 불러온다", async () => {
    const user = userEvent.setup({ delay: null });
    const { fetchMock, invalidated } = renderMenu();

    await user.click(screen.getByRole("button", { name: "수정" }));
    const textarea = screen.getByLabelText("댓글 수정");
    expect(textarea).toHaveValue("원래 댓글");
    expect(screen.getByText("5/300")).toBeInTheDocument();
    await user.clear(textarea);
    await user.type(textarea, "고친 댓글");
    await user.click(screen.getByRole("button", { name: "저장" }));

    await waitFor(() =>
      expect(screen.queryByLabelText("댓글 수정")).not.toBeInTheDocument(),
    );
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/comments/11",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({ content: "고친 댓글" }),
      }),
    );
    expect(invalidated(["posts", 7, "comments"])).toBe(true);
    expect(invalidated(["posts", 7])).toBe(true);
  });

  it("US4-AC3 빈 댓글로는 고치지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const { fetchMock } = renderMenu();

    await user.click(screen.getByRole("button", { name: "수정" }));
    await user.clear(screen.getByLabelText("댓글 수정"));
    await user.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByText("댓글을 적어 주세요.")).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US4-AC3 삭제는 확인 대화상자를 거쳐 DELETE를 보내고 window.confirm을 쓰지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const { fetchMock, confirmSpy, invalidated } = renderMenu();

    await user.click(screen.getByRole("button", { name: "삭제" }));
    expect(
      screen.getByRole("alertdialog", { name: "댓글을 지울까요?" }),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "삭제하기" }));

    await waitFor(() =>
      expect(fetchMock).toHaveBeenCalledWith(
        "/api/comments/11",
        expect.objectContaining({ method: "DELETE" }),
      ),
    );
    await waitFor(() => expect(invalidated(["posts", 7])).toBe(true));
    expect(invalidated(["posts", 7, "comments"])).toBe(true);
    expect(confirmSpy).not.toHaveBeenCalled();
  });
});
