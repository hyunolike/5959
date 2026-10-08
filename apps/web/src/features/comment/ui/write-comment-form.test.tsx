import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Comment } from "@/entities/comment";
import { QUERY_KEYS } from "@/shared/config";

import { WriteCommentForm } from "./write-comment-form";

const PARENT: Comment = {
  commentId: 11,
  author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
  content: "힘내요",
  likeCount: 0,
  likedByMe: false,
  mine: false,
  createdAt: "2026-10-03T00:00:00Z",
  replies: [],
};

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const created = () =>
  jsonResponse({ success: true, data: { ...PARENT, commentId: 12 } }, 201);

const errorResponse = (status: number, code: string, message: string) =>
  jsonResponse(
    { success: false, data: null, error: { code, message } },
    status,
  );

/** 위젯처럼 답글 대상을 상태로 들고, 답글 버튼과 폼을 함께 그린다. */
function StatefulForm() {
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  return (
    <>
      <button type="button" onClick={() => setReplyTo(PARENT)}>
        공감러에게 답글
      </button>
      <WriteCommentForm
        postId={7}
        replyTo={replyTo}
        onReplyDone={() => setReplyTo(null)}
      />
    </>
  );
}

function renderForm(
  props: Partial<Parameters<typeof WriteCommentForm>[0]>,
  { stateful = false } = {},
) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      {stateful ? <StatefulForm /> : <WriteCommentForm postId={7} {...props} />}
    </QueryClientProvider>,
  );
  return queryClient;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("WriteCommentForm", () => {
  it("US3-AC2 댓글을 쓰면 같은 출처 /api/posts/{id}/comments로 보내고, 성공하면 입력을 비우고 상세와 댓글을 다시 불러온다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(created());
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = renderForm({});
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), { postId: 7 });

    await user.type(screen.getByLabelText("댓글"), "  힘내요  ");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(screen.getByLabelText("댓글")).toHaveValue(""));
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts/7/comments",
      expect.objectContaining({ method: "POST" }),
    );
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      content: "힘내요",
    });
    expect(
      queryClient.getQueryState(QUERY_KEYS.postDetail(7))?.isInvalidated,
    ).toBe(true);
  });

  it("US3-AC10 보내기 전에 공격을 낙관적으로 반영하고, 실패하면 되돌린다", async () => {
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          errorResponse(500, "INTERNAL_ERROR", "잠시 문제가 생겼습니다."),
        ),
    );
    const rollback = vi.fn();
    const onOptimisticAttack = vi.fn(() => rollback);
    renderForm({ onOptimisticAttack });

    await user.type(screen.getByLabelText("댓글"), "힘내요");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(rollback).toHaveBeenCalledTimes(1));
    expect(onOptimisticAttack).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "댓글을 올리지 못했습니다. 잠시 후 다시 시도해주세요.",
    );
    expect(screen.getByLabelText("댓글")).toHaveValue("힘내요");
  });

  it("글자 수를 300자 기준으로 보여 주고, 넘으면 보내지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn();
    vi.stubGlobal("fetch", fetchMock);
    renderForm({});

    expect(screen.getByText("0/300")).toBeInTheDocument();
    await user.click(screen.getByLabelText("댓글"));
    await user.paste("가".repeat(301));
    expect(screen.getByText("301/300")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(
      await screen.findByText("300자 이하로 적어 주세요."),
    ).toBeInTheDocument();
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("US3-AC3 답글 대상이 있으면 누구에게 다는 답글인지 보여 주고 parentId를 함께 보낸다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(created());
    vi.stubGlobal("fetch", fetchMock);
    const onReplyDone = vi.fn();
    renderForm({ replyTo: PARENT, onReplyDone });

    expect(screen.getByText("공감러님에게 답글")).toBeInTheDocument();
    await user.type(screen.getByLabelText("답글"), "저도요");
    await user.click(screen.getByRole("button", { name: "등록" }));

    await waitFor(() => expect(onReplyDone).toHaveBeenCalledTimes(1));
    expect(JSON.parse(fetchMock.mock.calls[0][1].body as string)).toEqual({
      content: "저도요",
      parentId: 11,
    });
  });

  it("답글 쓰기를 취소할 수 있다", async () => {
    const user = userEvent.setup({ delay: null });
    const onReplyDone = vi.fn();
    renderForm({ replyTo: PARENT, onReplyDone });

    await user.click(screen.getByRole("button", { name: "답글 취소" }));
    expect(onReplyDone).toHaveBeenCalledTimes(1);
  });

  it("답글 쓰기를 취소하면 입력 칸으로 초점을 돌려 바로 댓글을 쓸 수 있다", async () => {
    const user = userEvent.setup({ delay: null });
    renderForm({}, { stateful: true });

    await user.click(screen.getByRole("button", { name: "공감러에게 답글" }));
    expect(screen.getByLabelText("답글")).toHaveFocus();
    await user.click(screen.getByRole("button", { name: "답글 취소" }));

    expect(screen.getByLabelText("댓글")).toHaveFocus();
    expect(screen.queryByText("공감러님에게 답글")).not.toBeInTheDocument();
  });

  it.each([
    [400, "INVALID_PARENT_COMMENT", "답글에는 답글을 달 수 없어요."],
    [404, "COMMENT_NOT_FOUND", "답글을 달 댓글이 지워졌어요."],
  ])(
    "US3-AC3 답글이 %i %s로 거절되면 안내하고 답글 상태를 끝내 다시 쓸 때 막히지 않는다",
    async (status, code, message) => {
      const user = userEvent.setup({ delay: null });
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValue(errorResponse(status, code, "서버 문구")),
      );
      renderForm({}, { stateful: true });

      await user.click(screen.getByRole("button", { name: "공감러에게 답글" }));
      await user.type(screen.getByLabelText("답글"), "저도요");
      await user.click(screen.getByRole("button", { name: "등록" }));

      expect(await screen.findByRole("alert")).toHaveTextContent(message);
      expect(screen.queryByText("공감러님에게 답글")).not.toBeInTheDocument();
      expect(screen.getByLabelText("댓글")).toHaveValue("저도요");
    },
  );

  it.each([
    [400, "INVALID_PARENT_COMMENT", "답글에는 답글을 달 수 없어요."],
    [404, "COMMENT_NOT_FOUND", "답글을 달 댓글이 지워졌어요."],
    [404, "POST_NOT_FOUND", "삭제된 글이에요."],
  ])("US3-AC3 %i %s면 안내한다", async (status, code, message) => {
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(errorResponse(status, code, "서버 문구")),
    );
    renderForm({ replyTo: PARENT });

    await user.type(screen.getByLabelText("답글"), "저도요");
    await user.click(screen.getByRole("button", { name: "등록" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(message);
  });
});
