import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { SimilarPosts } from "./similar-posts";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function item(postId: number, contentPreview: string) {
  return {
    postId,
    author: {
      id: postId + 100,
      nickname: `오구${postId}`,
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
    },
    contentPreview,
    monster: { emotion: "ANXIETY", hp: 10, maxHp: 10, status: "ALIVE" },
    likeCount: 0,
    commentCount: 0,
    createdAt: "2026-10-10T00:00:00Z",
  };
}

const ok = (data: unknown) =>
  jsonResponse({ success: true, data, error: null });

function renderSection(...responses: Response[]) {
  const fetchMock = vi.fn();
  for (const response of responses) {
    fetchMock.mockResolvedValueOnce(response);
  }
  vi.stubGlobal("fetch", fetchMock);
  const queryClient = new QueryClient();
  const view = render(
    <QueryClientProvider client={queryClient}>
      <SimilarPosts postId={7} />
    </QueryClientProvider>,
  );
  return { fetchMock, ...view };
}

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("SimilarPosts", () => {
  it("US1-AC1 비슷한 고민을 받은 순서대로 카드로 보여 준다", async () => {
    renderSection(
      ok({
        basis: "SIMILAR",
        items: [item(3, "팀장님이 무서워요"), item(5, "팀장님 눈치가 보여요")],
        pending: false,
      }),
    );

    const section = await screen.findByRole("region", { name: "비슷한 고민" });
    const cards = within(section).getAllByRole("link");
    expect(cards).toHaveLength(2);
    expect(cards[0]).toHaveTextContent("팀장님이 무서워요");
    expect(cards[1]).toHaveTextContent("팀장님 눈치가 보여요");
  });

  it("US1-AC2 카드를 누르면 그 글의 상세로 간다", async () => {
    renderSection(
      ok({
        basis: "SIMILAR",
        items: [item(3, "팀장님이 무서워요")],
        pending: false,
      }),
    );

    expect(await screen.findByRole("link")).toHaveAttribute("href", "/post/3");
  });

  it("US2-AC2 같은 감정으로 고른 글은 비슷한 고민이라 부르지 않는다", async () => {
    renderSection(
      ok({
        basis: "SAME_EMOTION",
        items: [item(3, "불안해요")],
        pending: false,
      }),
    );

    expect(
      await screen.findByRole("region", { name: "같은 감정의 고민" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("비슷한 고민")).not.toBeInTheDocument();
  });

  it("US2-AC3 보여 줄 글이 없으면 구역을 그리지 않는다", async () => {
    const { fetchMock, container } = renderSection(
      ok({ basis: "NONE", items: [], pending: false }),
    );

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});
    expect(container).toBeEmptyDOMElement();
  });

  it("US2-AC7 불러오지 못해도 오류를 보이지 않고 구역을 그리지 않는다", async () => {
    const { fetchMock, container } = renderSection(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "오류" },
        },
        500,
      ),
    );

    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    await act(async () => {});
    expect(container).toBeEmptyDOMElement();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("US1-AC7 준비 중이면 다시 불러와 새로고침 없이 비슷한 고민으로 바꾼다", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const { fetchMock } = renderSection(
      ok({
        basis: "SAME_EMOTION",
        items: [item(3, "불안해요")],
        pending: true,
      }),
      ok({
        basis: "SIMILAR",
        items: [item(5, "발표가 걱정돼요")],
        pending: false,
      }),
    );

    await screen.findByRole("region", { name: "같은 감정의 고민" });
    await act(() => vi.advanceTimersByTimeAsync(3_000));

    expect(
      await screen.findByRole("region", { name: "비슷한 고민" }),
    ).toHaveTextContent("발표가 걱정돼요");
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
});
