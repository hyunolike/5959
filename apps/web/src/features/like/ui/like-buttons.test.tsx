import {
  QueryClient,
  QueryClientProvider,
  useQuery,
} from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { Comment } from "@/entities/comment";
import type { PostDetail } from "@/entities/post";
import { QUERY_KEYS } from "@/shared/config";

import { CommentLikeButton } from "./comment-like-button";
import { PostLikeButton } from "./post-like-button";

function detail(overrides: Partial<PostDetail> = {}): PostDetail {
  return {
    postId: 7,
    author: {
      id: 1,
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
    },
    content: "내일 발표가 걱정돼요",
    commentTone: "COMFORT_ME",
    analysisStatus: "ANALYZED",
    monster: { emotion: "ANXIETY", hp: 10, maxHp: 10, status: "ALIVE" },
    likeCount: 2,
    likedByMe: false,
    commentCount: 0,
    mine: false,
    myCommentCounted: false,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

const COMMENT: Comment = {
  commentId: 11,
  author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
  content: "힘내요",
  likeCount: 4,
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

function renderWithClient(node: ReactNode) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
  render(
    <QueryClientProvider client={queryClient}>{node}</QueryClientProvider>,
  );
  return queryClient;
}

/** 화면처럼 상세 캐시를 읽어 버튼에 넘긴다. 다시 불러오기는 끝나지 않게 둔다. */
function CachedPostLikeButton() {
  const { data } = useQuery({
    queryKey: QUERY_KEYS.postDetail(7),
    queryFn: () => new Promise<PostDetail>(() => {}),
  });
  return data ? <PostLikeButton detail={data} /> : null;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("PostLikeButton", () => {
  it("US3-AC8 내 글이면 공감 버튼을 보여 주지 않는다", () => {
    renderWithClient(<PostLikeButton detail={detail({ mine: true })} />);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("US3-AC1 다른 회원의 글이면 공감 수와 함께 공감 버튼을 보여 주고, 누르면 공감한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: { likeCount: 3, likedByMe: true },
        error: null,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = renderWithClient(<PostLikeButton detail={detail()} />);

    const button = screen.getByRole("button", { name: "공감 2" });
    expect(button).toHaveAttribute("aria-pressed", "false");
    await user.click(button);

    expect(fetchMock).toHaveBeenCalledWith("/api/posts/7/likes", {
      method: "POST",
    });
    await waitFor(() =>
      expect(
        queryClient.getQueryData<PostDetail>(QUERY_KEYS.postDetail(7))?.monster
          ?.hp,
      ).toBe(9),
    );
  });

  it("US3-AC6 공감한 상태에서 누르면 공감을 취소한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: { likeCount: 1, likedByMe: false },
        error: null,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderWithClient(
      <PostLikeButton detail={detail({ likedByMe: true, likeCount: 2 })} />,
    );

    const button = screen.getByRole("button", { name: "공감 2" });
    expect(button).toHaveAttribute("aria-pressed", "true");
    await user.click(button);

    expect(fetchMock).toHaveBeenCalledWith("/api/posts/7/likes/me", {
      method: "DELETE",
    });
  });

  it("처음부터 공감해 둔 글을 취소했다가 다시 공감하면 HP를 줄여 보여 주지 않는다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({
          success: true,
          data: { likeCount: 1, likedByMe: false },
          error: null,
        }),
      )
      .mockReturnValue(new Promise<Response>(() => {}));
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = renderWithClient(<CachedPostLikeButton />);
    queryClient.setQueryData(
      QUERY_KEYS.postDetail(7),
      detail({ likedByMe: true }),
    );

    await user.click(await screen.findByRole("button", { name: "공감 2" }));
    await waitFor(() =>
      expect(screen.getByRole("button")).toHaveAttribute(
        "aria-pressed",
        "false",
      ),
    );
    await user.click(screen.getByRole("button"));

    await waitFor(() =>
      expect(screen.getByRole("button")).toHaveAttribute(
        "aria-pressed",
        "true",
      ),
    );
    expect(
      queryClient.getQueryData<PostDetail>(QUERY_KEYS.postDetail(7))?.monster
        ?.hp,
    ).toBe(10);
  });
});

describe("PostLikeButton 409", () => {
  it("US3-AC1 이미 공감한 글(409 ALREADY_LIKED)이면 안내 없이 공감한 상태로 남는다", async () => {
    const user = userEvent.setup({ delay: null });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse(
          {
            success: false,
            data: null,
            error: { code: "ALREADY_LIKED", message: "이미 공감했습니다." },
          },
          409,
        ),
      ),
    );
    renderWithClient(<CachedPostLikeButton />);

    await user.click(await screen.findByRole("button", { name: "공감 2" }));

    const button = await screen.findByRole("button", { name: "공감 3" });
    await waitFor(() => expect(button).toBeEnabled());
    expect(button).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("CommentLikeButton", () => {
  it("US3-AC4 댓글의 공감 수를 보여 주고, 누르면 그 댓글에 공감한다", async () => {
    const user = userEvent.setup({ delay: null });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({
        success: true,
        data: { likeCount: 5, likedByMe: true },
        error: null,
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    renderWithClient(<CommentLikeButton postId={7} comment={COMMENT} />);

    const button = screen.getByRole("button", { name: "댓글 공감 4" });
    expect(button).toHaveAttribute("aria-pressed", "false");
    await user.click(button);

    expect(fetchMock).toHaveBeenCalledWith("/api/comments/11/likes", {
      method: "POST",
    });
  });
});
