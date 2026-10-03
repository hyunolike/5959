import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
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
