import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const pushMock = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: pushMock }),
}));

import { PostEditor } from "./post-editor";

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
    content: "원래 고민",
    commentTone: "COMFORT_ME",
    analysisStatus: "ANALYZED",
    monster: { emotion: "ANXIETY", hp: 10, maxHp: 10, status: "ALIVE" },
    likeCount: 0,
    likedByMe: false,
    commentCount: 0,
    mine: true,
    myCommentCounted: false,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

function renderEditor(
  postDetail: Record<string, unknown>,
  patchResponse: Response = new Response(null, { status: 204 }),
) {
  const fetchMock = vi.fn((_url: string, init?: RequestInit) =>
    Promise.resolve(
      init?.method === "PATCH"
        ? patchResponse
        : jsonResponse({ success: true, data: postDetail, error: null }),
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <PostEditor postId={7} />
    </QueryClientProvider>,
  );
  return fetchMock;
}

afterEach(() => {
  vi.unstubAllGlobals();
  pushMock.mockReset();
});

describe("PostEditor", () => {
  it("US4-AC1 작성 폼에 지금 본문과 말투를 채워 보여 주고, 고치면 PATCH를 보내고 상세로 돌아간다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = renderEditor(detail());

    const textarea = await screen.findByLabelText("고민");
    expect(textarea).toHaveValue("원래 고민");
    expect(
      screen.getByRole("button", { name: "무조건 위로해주기" }),
    ).toHaveAttribute("aria-pressed", "true");

    await user.clear(textarea);
    await user.type(textarea, "고친 고민");
    await user.click(screen.getByRole("button", { name: "웃겨주기" }));
    await user.click(screen.getByRole("button", { name: "고치기" }));

    await waitFor(() => expect(pushMock).toHaveBeenCalledWith("/post/7"));
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts/7",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({
          content: "고친 고민",
          commentTone: "MAKE_ME_LAUGH",
        }),
      }),
    );
  });

  it("US4-AC4 내 글이 아니면 폼을 보여 주지 않는다", async () => {
    renderEditor(detail({ mine: false }));

    expect(
      await screen.findByText("내 글만 고칠 수 있어요."),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("고민")).not.toBeInTheDocument();
  });

  it("US4-AC1 고칠 때도 작성과 같은 검증으로 501자는 보내지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = renderEditor(detail());

    const textarea = await screen.findByLabelText("고민");
    await user.clear(textarea);
    await user.click(textarea);
    await user.paste("가".repeat(501));
    await user.click(screen.getByRole("button", { name: "고치기" }));

    expect(
      await screen.findByText("500자 이하로 적어 주세요."),
    ).toBeInTheDocument();
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
});
