import {
  QueryClient,
  QueryClientProvider,
  useMutation,
} from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ApiError } from "@/shared/api";
import { MUTATION_KEYS } from "@/shared/config";

import type { PostDetail } from "../model/types";
import {
  ANALYSIS_POLL_FAST_MS,
  ANALYSIS_POLL_SLOW_MS,
  ANALYSIS_POLL_SLOWDOWN_AFTER_MS,
  analysisPollInterval,
  fetchPostDetail,
  usePostDetailQuery,
} from "./use-post-detail-query";

const MONSTER = {
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

/**
 * 진짜 `Response`의 본문 읽기는 jsdom에서 가짜 시계 밖의 비동기로 끝나 폴링
 * 타이밍을 흐린다. `status`와 `json()`만 있는 응답으로 마이크로태스크 안에서 끝낸다.
 */
const jsonResponse = (body: unknown, status = 200) =>
  ({ status, json: () => Promise.resolve(body) }) as unknown as Response;

const ok = (data: PostDetail) =>
  jsonResponse({ success: true, data, error: null });

/** research R10: PENDING 동안 3초, 2분이 지나면 15초, 몬스터가 생기면 멈춘다. */
describe("analysisPollInterval", () => {
  it("US1-AC3 분석 중(PENDING, 몬스터 없음)이면 3초마다 다시 불러온다", () => {
    expect(analysisPollInterval(detail(), 0)).toBe(ANALYSIS_POLL_FAST_MS);
    expect(ANALYSIS_POLL_FAST_MS).toBe(3_000);
  });

  it("US1-AC3 2분이 지나도 분석 중이면 15초 간격으로 늦춘다", () => {
    expect(ANALYSIS_POLL_SLOWDOWN_AFTER_MS).toBe(120_000);
    expect(ANALYSIS_POLL_SLOW_MS).toBe(15_000);
    expect(
      analysisPollInterval(detail(), ANALYSIS_POLL_SLOWDOWN_AFTER_MS - 1),
    ).toBe(ANALYSIS_POLL_FAST_MS);
    expect(
      analysisPollInterval(detail(), ANALYSIS_POLL_SLOWDOWN_AFTER_MS),
    ).toBe(ANALYSIS_POLL_SLOW_MS);
  });

  it.each(["ANALYZED", "DEFAULTED"] as const)(
    "US1-AC3 %s이고 몬스터가 있으면 멈춘다",
    (analysisStatus) => {
      expect(
        analysisPollInterval(detail({ analysisStatus, monster: MONSTER }), 0),
      ).toBe(false);
    },
  );

  it.each(["ANALYZED", "DEFAULTED"] as const)(
    "US1-AC3 %s여도 몬스터가 아직 없으면(비동기 생성 전) 계속 불러온다",
    (analysisStatus) => {
      expect(analysisPollInterval(detail({ analysisStatus }), 0)).toBe(
        ANALYSIS_POLL_FAST_MS,
      );
    },
  );

  it("상세를 아직 받지 못했으면 폴링하지 않는다", () => {
    expect(analysisPollInterval(undefined, 0)).toBe(false);
  });
});

describe("fetchPostDetail", () => {
  it("같은 출처 BFF 프록시(/api/posts/{id})로 상세를 가져온다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(ok(detail()));
    await expect(fetchPostDetail(7, fetchImpl)).resolves.toEqual(detail());
    expect(fetchImpl).toHaveBeenCalledWith("/api/posts/7", {
      cache: "no-store",
    });
  });

  it("404 POST_NOT_FOUND는 ApiError로 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "POST_NOT_FOUND", message: "글이 없습니다." },
        },
        404,
      ),
    );
    const error = await fetchPostDetail(7, fetchImpl).catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.code).toBe("POST_NOT_FOUND");
  });
});

/**
 * 응답을 받은 뒤 화면이 다시 그려지기까지 TanStack Query와 React가 짧은
 * 타이머를 몇 번 거친다. 폴링 간격(3초)보다 훨씬 짧은 이 시간만큼 더 흘려
 * 보내야 `result.current`가 새 응답을 담는다.
 */
const RENDER_SETTLE_MS = 50;

/** 가짜 시계를 `ms`만큼 움직이고 새 응답이 화면에 반영될 때까지 기다린다. */
async function advance(ms: number) {
  await act(() => vi.advanceTimersByTimeAsync(ms));
  await act(() => vi.advanceTimersByTimeAsync(RENDER_SETTLE_MS));
}

describe("usePostDetailQuery 폴링", () => {
  let queryClient: QueryClient;

  function wrapper({ children }: { children: ReactNode }) {
    return createElement(
      QueryClientProvider,
      { client: queryClient },
      children,
    );
  }

  beforeEach(() => {
    vi.useFakeTimers();
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
  });

  afterEach(() => {
    queryClient.clear();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("US1-AC3 분석 중이면 3초마다 다시 부르고, 몬스터가 생기면 멈춘다", async () => {
    const responses = [
      detail(),
      detail(),
      // 분석은 끝났지만 몬스터가 아직 비동기로 만들어지기 전이다.
      detail({ analysisStatus: "ANALYZED" }),
      detail({ analysisStatus: "ANALYZED", monster: MONSTER }),
    ];
    const fetchMock = vi.fn(() =>
      Promise.resolve(ok(responses.shift() ?? detail())),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => usePostDetailQuery(7), { wrapper });
    await advance(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    // 화면처럼 data를 읽어야 TanStack Query가 data 변화에 다시 그린다(tracked props).
    expect(result.current.data?.analysisStatus).toBe("PENDING");

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(result.current.data?.analysisStatus).toBe("ANALYZED");
    expect(result.current.data?.monster).toBeNull();

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(result.current.data?.monster).toEqual(MONSTER);

    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOW_MS * 4);
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });

  it("US1-AC3 첫 조회부터 2분이 지나면 15초 간격으로 늦춘다", async () => {
    const fetchMock = vi.fn(() => Promise.resolve(ok(detail())));
    vi.stubGlobal("fetch", fetchMock);

    renderHook(() => usePostDetailQuery(7), { wrapper });
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOWDOWN_AFTER_MS);
    const callsAtTwoMinutes = fetchMock.mock.calls.length;
    // 2분 동안 3초 간격: 첫 조회 1번 + 40번
    expect(callsAtTwoMinutes).toBe(41);

    // 2분째 조회부터는 다음 조회가 15초 뒤다.
    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOW_MS - 1);
    expect(fetchMock.mock.calls.length).toBe(callsAtTwoMinutes);
    await vi.advanceTimersByTimeAsync(1);
    expect(fetchMock.mock.calls.length).toBe(callsAtTwoMinutes + 1);
    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOW_MS);
    expect(fetchMock.mock.calls.length).toBe(callsAtTwoMinutes + 2);
  });

  it("처음부터 몬스터가 있으면 다시 부르지 않는다", async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(
        ok(detail({ analysisStatus: "ANALYZED", monster: MONSTER })),
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    renderHook(() => usePostDetailQuery(7), { wrapper });
    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOWDOWN_AFTER_MS);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("US1-AC3 재시도 끝에 500이 나도 폴링을 멈추지 않고, 이후 몬스터가 생기면 보여 준다", async () => {
    const serverError = () =>
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "잠시 문제가 생겼습니다." },
        },
        500,
      );
    const responses = [
      () => ok(detail()),
      serverError,
      () => ok(detail()),
      () => ok(detail({ analysisStatus: "ANALYZED", monster: MONSTER })),
    ];
    const fetchMock = vi.fn(() =>
      Promise.resolve((responses.shift() ?? (() => ok(detail())))()),
    );
    vi.stubGlobal("fetch", fetchMock);

    // 이 테스트의 QueryClient는 retry: false라 500 한 번이 곧 "재시도 끝의 실패"다.
    const { result } = renderHook(() => usePostDetailQuery(7), { wrapper });
    await advance(0);
    expect(result.current.data?.monster).toBeNull();
    expect(result.current.error).toBeNull();

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(result.current.error).toBeInstanceOf(ApiError);

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);

    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(4);
    expect(result.current.data?.monster).toEqual(MONSTER);
  });

  it("네트워크 오류가 나도 폴링을 이어 간다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(ok(detail()))
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(ok(detail()));
    vi.stubGlobal("fetch", fetchMock);

    renderHook(() => usePostDetailQuery(7), { wrapper });
    await advance(0);
    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("분석 중에 404(지운 글)가 나면 폴링을 멈춘다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValue(
        jsonResponse(
          {
            success: false,
            data: null,
            error: { code: "POST_NOT_FOUND", message: "글이 없습니다." },
          },
          404,
        ),
      );
    vi.stubGlobal("fetch", fetchMock);

    renderHook(() => usePostDetailQuery(7), { wrapper });
    await advance(0);
    await advance(ANALYSIS_POLL_FAST_MS);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    await advance(ANALYSIS_POLL_SLOW_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("404면 다시 부르지 않는다", async () => {
    const fetchMock = vi.fn(() =>
      Promise.resolve(
        jsonResponse(
          {
            success: false,
            data: null,
            error: { code: "POST_NOT_FOUND", message: "글이 없습니다." },
          },
          404,
        ),
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => usePostDetailQuery(7), { wrapper });
    await vi.advanceTimersByTimeAsync(ANALYSIS_POLL_SLOW_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.current.error).toBeInstanceOf(ApiError);
  });

  it('분석 중인 글을 지우는 동안과 지운 뒤에는 폴링하지 않는다(이동 전에 "삭제된 글이에요"가 깜박이지 않는다)', async () => {
    const fetchMock = vi.fn(() => Promise.resolve(ok(detail())));
    vi.stubGlobal("fetch", fetchMock);
    let finishDelete: () => void = () => {};
    const deleteRequest = () =>
      new Promise<void>((resolve) => {
        finishDelete = resolve;
      });

    const { result } = renderHook(
      () => ({
        detail: usePostDetailQuery(7),
        remove: useMutation({
          mutationKey: MUTATION_KEYS.deletePost(7),
          mutationFn: deleteRequest,
        }),
      }),
      { wrapper },
    );
    await advance(0);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.current.detail.data?.monster).toBeNull();

    act(() => result.current.remove.mutate());
    await advance(ANALYSIS_POLL_FAST_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);

    await act(async () => finishDelete());
    await advance(0);
    expect(result.current.remove.isSuccess).toBe(true);
    await advance(ANALYSIS_POLL_FAST_MS * 2);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(result.current.detail.data?.postId).toBe(7);
  });

  it("지운 뒤 상세 캐시를 지우고 같은 글을 다시 열면 다시 불러와 404(삭제된 글)로 끝난다", async () => {
    const notFound = jsonResponse(
      {
        success: false,
        data: null,
        error: { code: "POST_NOT_FOUND", message: "글이 없습니다." },
      },
      404,
    );
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValue(notFound);
    vi.stubGlobal("fetch", fetchMock);

    const first = renderHook(
      () => ({
        detail: usePostDetailQuery(7),
        remove: useMutation({
          mutationKey: MUTATION_KEYS.deletePost(7),
          mutationFn: () => Promise.resolve(),
        }),
      }),
      { wrapper },
    );
    await advance(0);
    await act(async () => first.result.current.remove.mutate());
    await advance(0);
    expect(first.result.current.remove.isSuccess).toBe(true);
    first.unmount();
    // 화면을 떠나면 forgetDeletedPost가 상세 캐시를 지운다. 성공한 뮤테이션은 캐시에 남아 있다.
    queryClient.removeQueries({ queryKey: ["posts", 7] });

    const again = renderHook(() => usePostDetailQuery(7), { wrapper });
    await advance(0);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(again.result.current.error).toBeInstanceOf(ApiError);
    expect((again.result.current.error as ApiError).status).toBe(404);
    expect(again.result.current.isPending).toBe(false);
  });
});
