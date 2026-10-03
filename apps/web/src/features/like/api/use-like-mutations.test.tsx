import {
  QueryClient,
  QueryClientProvider,
  useQuery,
  type InfiniteData,
} from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { Comment, CommentPage } from "@/entities/comment";
import type { PostDetail } from "@/entities/post";
import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import { attackOptimistically } from "./optimistic-cache";
import { useCommentLikeMutation } from "./use-comment-like-mutation";
import { usePostLikeMutation } from "./use-post-like-mutation";

const ALIVE = {
  emotion: "ANXIETY",
  hp: 10,
  maxHp: 10,
  status: "ALIVE",
} as const;

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
    monster: ALIVE,
    likeCount: 0,
    likedByMe: false,
    commentCount: 1,
    mine: false,
    myCommentCounted: false,
    createdAt: "2026-10-03T00:00:00Z",
    ...overrides,
  };
}

function comment(overrides: Partial<Comment> = {}): Comment {
  return {
    commentId: 11,
    author: { id: 2, nickname: "공감러", jobRole: "HR", careerYear: "YEAR_2" },
    content: "힘내요",
    likeCount: 0,
    likedByMe: false,
    mine: false,
    createdAt: "2026-10-03T00:00:00Z",
    replies: [],
    ...overrides,
  };
}

const jsonResponse = (body: unknown, status = 200) =>
  ({ status, json: () => Promise.resolve(body) }) as unknown as Response;

const likeResult = (likeCount: number, likedByMe: boolean) =>
  jsonResponse({ success: true, data: { likeCount, likedByMe }, error: null });

const serverError = () =>
  jsonResponse(
    {
      success: false,
      data: null,
      error: { code: "INTERNAL_ERROR", message: "잠시 문제가 생겼습니다." },
    },
    500,
  );

/** 응답을 테스트가 정한 때에 돌려주는 fetch. 응답 전 화면(낙관적 상태)을 보려고 쓴다. */
function deferredFetch() {
  let resolve: (response: Response) => void = () => {};
  const fetchMock = vi.fn(
    () =>
      new Promise<Response>((r) => {
        resolve = r;
      }),
  );
  vi.stubGlobal("fetch", fetchMock);
  return { fetchMock, respond: (response: Response) => resolve(response) };
}

let queryClient: QueryClient;

function wrapper({ children }: { children: ReactNode }) {
  return createElement(QueryClientProvider, { client: queryClient }, children);
}

const detailOf = () =>
  queryClient.getQueryData<PostDetail>(QUERY_KEYS.postDetail(7));
const commentsOf = () =>
  queryClient.getQueryData<InfiniteData<CommentPage>>(QUERY_KEYS.comments(7));

function seedComments(items: Comment[]) {
  queryClient.setQueryData<InfiniteData<CommentPage>>(QUERY_KEYS.comments(7), {
    pages: [{ items, nextCursor: null }],
    pageParams: [null],
  });
}

beforeEach(() => {
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
});

afterEach(() => {
  queryClient.clear();
  vi.unstubAllGlobals();
});

describe("usePostLikeMutation", () => {
  it("US3-AC10 공감하면 응답 전에 공감 수와 HP를 바꿔 보여 주고, 성공하면 상세를 다시 불러온다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    const { fetchMock, respond } = deferredFetch();
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: true }));

    await waitFor(() => expect(detailOf()?.likedByMe).toBe(true));
    expect(detailOf()?.likeCount).toBe(1);
    expect(detailOf()?.monster?.hp).toBe(9);
    expect(fetchMock).toHaveBeenCalledWith("/api/posts/7/likes", {
      method: "POST",
    });

    respond(likeResult(1, true));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(
      queryClient.getQueryState(QUERY_KEYS.postDetail(7))?.isInvalidated,
    ).toBe(true);
  });

  it("US3-AC6 공감을 취소하면 공감 수는 줄지만 HP는 돌아오지 않는다", async () => {
    queryClient.setQueryData(
      QUERY_KEYS.postDetail(7),
      detail({ likeCount: 1, likedByMe: true, monster: { ...ALIVE, hp: 9 } }),
    );
    const { fetchMock, respond } = deferredFetch();
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: false }));

    await waitFor(() => expect(detailOf()?.likedByMe).toBe(false));
    expect(detailOf()?.likeCount).toBe(0);
    expect(detailOf()?.monster?.hp).toBe(9);
    expect(fetchMock).toHaveBeenCalledWith("/api/posts/7/likes/me", {
      method: "DELETE",
    });
    respond(likeResult(0, false));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("이미 반영된 공감을 다시 하면 HP는 줄여 보여 주지 않는다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    deferredFetch();
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: true, alreadyApplied: true }));

    await waitFor(() => expect(detailOf()?.likeCount).toBe(1));
    expect(detailOf()?.monster?.hp).toBe(10);
  });

  it("실패하면 공감 상태와 HP를 되돌린다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(serverError()));
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: true }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(detailOf()).toEqual(detail());
  });
});

describe("usePostLikeMutation 새로고침과 409", () => {
  it("글 공감이 끝나면 상세만 다시 불러오고 댓글 목록은 건드리지 않는다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    seedComments([comment()]);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(likeResult(1, true)));
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: true }));

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(
      queryClient.getQueryState(QUERY_KEYS.postDetail(7))?.isInvalidated,
    ).toBe(true);
    expect(
      queryClient.getQueryState(QUERY_KEYS.comments(7))?.isInvalidated,
    ).toBe(false);
  });

  it("US3-AC1 이미 공감한 글(409 ALREADY_LIKED)이면 공감한 상태를 그대로 두고 낙관적 HP만 되돌린다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
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
    const { result } = renderHook(() => usePostLikeMutation(7), { wrapper });

    act(() => result.current.mutate({ like: true }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(detailOf()?.likedByMe).toBe(true);
    expect(detailOf()?.likeCount).toBe(1);
    // 서버는 이 공감을 이미 반영했으므로 다시 줄인 1은 되돌린다.
    expect(detailOf()?.monster?.hp).toBe(10);
  });
});

describe("겹친 공격", () => {
  it("US3-AC10 공격이 겹치면 마지막 공격이 끝날 때만 다시 불러와 HP가 중간에 오르지 않고, 끝내 서버 값이 들어온다", async () => {
    let serverHp = 10;
    const held = new Map<string, (response: Response) => void>();
    const fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "POST") {
        return new Promise<Response>((resolve) => held.set(url, resolve));
      }
      if (url === "/api/posts/7") {
        return Promise.resolve(
          jsonResponse({
            success: true,
            data: detail({
              monster: { ...ALIVE, hp: serverHp },
              likedByMe: serverHp < 10,
              likeCount: serverHp < 10 ? 1 : 0,
            }),
            error: null,
          }),
        );
      }
      return Promise.resolve(
        jsonResponse({
          success: true,
          data: { items: [comment()], nextCursor: null },
          error: null,
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    seedComments([comment()]);
    const hpHistory: (number | undefined)[] = [];
    const unsubscribe = queryClient
      .getQueryCache()
      .subscribe(() => hpHistory.push(detailOf()?.monster?.hp));
    const detailGets = () =>
      fetchMock.mock.calls.filter(([url]) => url === "/api/posts/7").length;

    const { result } = renderHook(
      () => ({
        // 화면처럼 상세를 지켜보는 쿼리. 무효화되면 바로 다시 불러온다.
        detail: useQuery({
          queryKey: QUERY_KEYS.postDetail(7),
          queryFn: () => requestApi<PostDetail>("/api/posts/7"),
          staleTime: Infinity,
        }),
        postLike: usePostLikeMutation(7),
        commentLike: useCommentLikeMutation(7),
      }),
      { wrapper },
    );

    act(() => result.current.postLike.mutate({ like: true }));
    await waitFor(() => expect(detailOf()?.monster?.hp).toBe(9));
    act(() => result.current.commentLike.mutate({ commentId: 11, like: true }));
    await waitFor(() => expect(detailOf()?.monster?.hp).toBe(8));

    // 글 공감이 먼저 끝난다. 이때 다시 불러오면 댓글 공감이 빠진 HP 9가 들어온다.
    serverHp = 9;
    held.get("/api/posts/7/likes")?.(likeResult(1, true));
    await waitFor(() => expect(result.current.postLike.isSuccess).toBe(true));
    expect(detailGets()).toBe(0);
    expect(detailOf()?.monster?.hp).toBe(8);

    serverHp = 8;
    held.get("/api/comments/11/likes")?.(likeResult(1, true));
    await waitFor(() =>
      expect(result.current.commentLike.isSuccess).toBe(true),
    );
    await waitFor(() => expect(detailGets()).toBe(1));
    await waitFor(() =>
      expect(result.current.detail.data?.likedByMe).toBe(true),
    );
    expect(detailOf()?.monster?.hp).toBe(8);
    // 댓글 공감이 끼어 있었으므로 댓글 목록도 다시 불러온다.
    expect(
      fetchMock.mock.calls.some(([url]) =>
        String(url).startsWith("/api/posts/7/comments"),
      ) || queryClient.getQueryState(QUERY_KEYS.comments(7))?.isInvalidated,
    ).toBe(true);

    unsubscribe();
    const seen = hpHistory.filter((hp): hp is number => hp !== undefined);
    seen.forEach((hp, i) => {
      if (i > 0) expect(hp).toBeLessThanOrEqual(seen[i - 1]);
    });
  });
});

describe("useCommentLikeMutation", () => {
  it("US3-AC4 답글에 공감하면 그 답글의 공감 수가 늘고 HP가 1 준다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    seedComments([comment({ replies: [comment({ commentId: 12 })] })]);
    const { fetchMock, respond } = deferredFetch();
    const { result } = renderHook(() => useCommentLikeMutation(7), {
      wrapper,
    });

    act(() => result.current.mutate({ commentId: 12, like: true }));

    await waitFor(() =>
      expect(commentsOf()?.pages[0].items[0].replies[0].likedByMe).toBe(true),
    );
    expect(commentsOf()?.pages[0].items[0].replies[0].likeCount).toBe(1);
    expect(commentsOf()?.pages[0].items[0].likeCount).toBe(0);
    expect(detailOf()?.monster?.hp).toBe(9);
    expect(fetchMock).toHaveBeenCalledWith("/api/comments/12/likes", {
      method: "POST",
    });

    respond(likeResult(1, true));
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(
      queryClient.getQueryState(QUERY_KEYS.postDetail(7))?.isInvalidated,
    ).toBe(true);
    expect(
      queryClient.getQueryState(QUERY_KEYS.comments(7))?.isInvalidated,
    ).toBe(true);
  });

  it("US3-AC8 내 글의 댓글에 공감하면 공감 수는 늘지만 HP는 줄지 않는다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail({ mine: true }));
    seedComments([comment()]);
    deferredFetch();
    const { result } = renderHook(() => useCommentLikeMutation(7), {
      wrapper,
    });

    act(() => result.current.mutate({ commentId: 11, like: true }));

    await waitFor(() =>
      expect(commentsOf()?.pages[0].items[0].likeCount).toBe(1),
    );
    expect(detailOf()?.monster?.hp).toBe(10);
  });

  it("US3-AC6 댓글 공감을 취소하면 공감 수는 줄지만 HP는 돌아오지 않는다", async () => {
    queryClient.setQueryData(
      QUERY_KEYS.postDetail(7),
      detail({ monster: { ...ALIVE, hp: 9 } }),
    );
    seedComments([comment({ likeCount: 1, likedByMe: true })]);
    const { fetchMock } = deferredFetch();
    const { result } = renderHook(() => useCommentLikeMutation(7), {
      wrapper,
    });

    act(() => result.current.mutate({ commentId: 11, like: false }));

    await waitFor(() =>
      expect(commentsOf()?.pages[0].items[0].likeCount).toBe(0),
    );
    expect(detailOf()?.monster?.hp).toBe(9);
    expect(fetchMock).toHaveBeenCalledWith("/api/comments/11/likes/me", {
      method: "DELETE",
    });
  });

  it("실패하면 댓글 공감 상태와 HP를 되돌린다", async () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());
    seedComments([comment()]);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(serverError()));
    const { result } = renderHook(() => useCommentLikeMutation(7), {
      wrapper,
    });

    act(() => result.current.mutate({ commentId: 11, like: true }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(detailOf()).toEqual(detail());
    expect(commentsOf()?.pages[0].items[0]).toEqual(comment());
  });
});

describe("attackOptimistically", () => {
  it("US3-AC2 상세에 첫 댓글 공격을 반영하고, 되돌리면 원래 HP로 돌아간다", () => {
    queryClient.setQueryData(QUERY_KEYS.postDetail(7), detail());

    const rollback = attackOptimistically(queryClient, 7, "COMMENT");
    expect(detailOf()?.monster?.hp).toBe(7);
    expect(detailOf()?.myCommentCounted).toBe(true);

    rollback();
    expect(detailOf()).toEqual(detail());
  });

  it("상세를 아직 받지 않았으면 아무것도 하지 않는다", () => {
    const rollback = attackOptimistically(queryClient, 7, "COMMENT");
    rollback();
    expect(detailOf()).toBeUndefined();
  });
});
