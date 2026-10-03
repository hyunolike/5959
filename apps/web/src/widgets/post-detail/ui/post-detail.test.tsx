import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { PostDetail } from "./post-detail";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function detail(overrides: Record<string, unknown> = {}) {
  return {
    postId: 7,
    author: {
      id: 1,
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
    },
    content: "내일 발표가\n걱정돼요",
    commentTone: "COMFORT_ME",
    analysisStatus: "PENDING",
    monster: null,
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    mine: true,
    myCommentCounted: false,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

function renderDetail(response: Response) {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response));
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <PostDetail postId={7} />
    </QueryClientProvider>,
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("PostDetail", () => {
  it("US1-AC1 글 본문, 작성자 닉네임과 직군과 경력, 댓글 말투를 보여 준다", async () => {
    renderDetail(jsonResponse({ success: true, data: detail(), error: null }));

    expect(await screen.findByText("오구")).toBeInTheDocument();
    expect(screen.getByText("개발 · 1년차")).toBeInTheDocument();
    expect(screen.getByText(/내일 발표가/)).toBeInTheDocument();
    expect(screen.getByText("무조건 위로해주기")).toBeInTheDocument();
  });

  it("US1-AC3 몬스터가 아직 없으면 몬스터 자리에 분석 중을 보여 준다", async () => {
    renderDetail(jsonResponse({ success: true, data: detail(), error: null }));

    expect(await screen.findByText("분석 중")).toBeInTheDocument();
    expect(screen.queryByRole("progressbar")).not.toBeInTheDocument();
  });

  it("US1-AC3 분석이 끝났어도 몬스터가 만들어지기 전이면 분석 중으로 보여 준다", async () => {
    renderDetail(
      jsonResponse({
        success: true,
        data: detail({ analysisStatus: "ANALYZED" }),
        error: null,
      }),
    );

    expect(await screen.findByText("분석 중")).toBeInTheDocument();
  });

  it("US1-AC4 몬스터가 있으면 감정 이름과 HP 바를 보여 준다", async () => {
    renderDetail(
      jsonResponse({
        success: true,
        data: detail({
          analysisStatus: "ANALYZED",
          monster: { emotion: "ANXIETY", hp: 20, maxHp: 20, status: "ALIVE" },
        }),
        error: null,
      }),
    );

    expect(await screen.findByText("불안")).toBeInTheDocument();
    const bar = screen.getByRole("progressbar", { name: "몬스터 HP" });
    expect(bar).toHaveAttribute("aria-valuenow", "20");
    expect(bar).toHaveAttribute("aria-valuemax", "20");
    expect(screen.getByText("HP 20/20")).toBeInTheDocument();
    expect(screen.queryByText("분석 중")).not.toBeInTheDocument();
  });

  it("404 POST_NOT_FOUND면 삭제된 글이라고 안내한다", async () => {
    renderDetail(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "POST_NOT_FOUND", message: "글을 찾을 수 없습니다." },
        },
        404,
      ),
    );

    expect(await screen.findByText("삭제된 글이에요.")).toBeInTheDocument();
  });
});

const COMMENT = {
  commentId: 11,
  author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
  content: "힘내요",
  likeCount: 1,
  likedByMe: false,
  mine: false,
  createdAt: "2026-10-03T00:00:00Z",
  replies: [
    {
      commentId: 12,
      author: {
        id: 3,
        nickname: "답글러",
        jobRole: "SALES",
        careerYear: "YEAR_1",
      },
      content: "저도요",
      likeCount: 0,
      likedByMe: false,
      mine: false,
      createdAt: "2026-10-03T00:01:00Z",
      replies: [],
    },
  ],
};

/** 경로마다 다른 응답을 돌려준다(상세, 댓글 목록). */
function renderDetailWithComments(
  postDetail: Record<string, unknown>,
  comments: unknown[] = [COMMENT],
) {
  vi.stubGlobal(
    "fetch",
    vi.fn((url: string) =>
      Promise.resolve(
        url.startsWith("/api/posts/7/comments")
          ? jsonResponse({
              success: true,
              data: { items: comments, nextCursor: null },
              error: null,
            })
          : jsonResponse({ success: true, data: postDetail, error: null }),
      ),
    ),
  );
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <PostDetail postId={7} />
    </QueryClientProvider>,
  );
}

const ANALYZED = {
  analysisStatus: "ANALYZED",
  monster: { emotion: "ANXIETY", hp: 10, maxHp: 10, status: "ALIVE" },
};

describe("PostDetail 공감과 댓글", () => {
  it("US3-AC1 다른 회원의 글이면 공감 버튼과 댓글 수를 보여 준다", async () => {
    renderDetailWithComments(
      detail({ ...ANALYZED, mine: false, likeCount: 3, commentCount: 2 }),
    );

    expect(
      await screen.findByRole("button", { name: "공감 3" }),
    ).toBeInTheDocument();
    expect(screen.getByText("댓글 2")).toBeInTheDocument();
  });

  it("US3-AC8 내 글이면 공감 버튼 없이 공감 수만 보여 주고, 댓글은 쓸 수 있다", async () => {
    renderDetailWithComments(detail({ ...ANALYZED, mine: true, likeCount: 3 }));

    expect(await screen.findByText("공감 3")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "공감 3" }),
    ).not.toBeInTheDocument();
    expect(screen.getByLabelText("댓글")).toBeInTheDocument();
  });

  it("US3-AC3 댓글 아래에 답글을 보여 주고, 원 댓글에만 답글 버튼이 있다", async () => {
    const user = userEvent.setup({ delay: null });
    renderDetailWithComments(detail({ ...ANALYZED, mine: false }));

    const root = await screen.findByRole("article", {
      name: "공감러의 댓글",
    });
    const reply = screen.getByRole("article", { name: "답글러의 답글" });
    expect(
      within(root).getByRole("button", { name: "댓글 공감 1" }),
    ).toBeInTheDocument();
    expect(
      within(reply).queryByRole("button", { name: "답글 달기" }),
    ).not.toBeInTheDocument();

    await user.click(
      within(root).getAllByRole("button", { name: "답글 달기" })[0],
    );
    expect(screen.getByText("공감러님에게 답글")).toBeInTheDocument();
    expect(screen.getByLabelText("답글")).toHaveFocus();
  });

  it("댓글이 없으면 첫 댓글을 남겨 달라고 안내한다", async () => {
    renderDetailWithComments(detail({ ...ANALYZED, mine: false }), []);

    expect(
      await screen.findByText("첫 댓글을 남겨 주세요."),
    ).toBeInTheDocument();
  });
});
