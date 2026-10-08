"use client";

import { useMutationState, useQuery } from "@tanstack/react-query";
import { useState } from "react";

import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

import type { PostDetail } from "../model/types";

/** research R10: 분석 중에는 3초마다 다시 불러온다. */
export const ANALYSIS_POLL_FAST_MS = 3_000;
/** research R10: 2분이 지나도 분석 중이면 15초 간격으로 늦춘다. */
export const ANALYSIS_POLL_SLOW_MS = 15_000;
export const ANALYSIS_POLL_SLOWDOWN_AFTER_MS = 2 * 60 * 1_000;

/**
 * 다음 폴링까지의 간격. `false`면 멈춘다.
 *
 * 몬스터가 생겼는지로 판단한다. 분석이 끝나면 몬스터는 이벤트를 받아 비동기로
 * 만들어지므로, `analysisStatus`가 `ANALYZED`나 `DEFAULTED`여도 잠깐 `monster`가
 * `null`일 수 있다. 그동안은 아직 분석 중으로 보고 계속 불러온다.
 *
 * @param elapsedMs 이 화면에서 처음 불러온 뒤 지난 시간. 서버 시각(`createdAt`)과
 *   비교하지 않아 브라우저 시계가 틀려도 간격이 흔들리지 않는다.
 */
export function analysisPollInterval(
  detail: PostDetail | undefined,
  elapsedMs: number,
): number | false {
  if (!detail || detail.monster) {
    return false;
  }
  return elapsedMs < ANALYSIS_POLL_SLOWDOWN_AFTER_MS
    ? ANALYSIS_POLL_FAST_MS
    : ANALYSIS_POLL_SLOW_MS;
}

/**
 * 같은 출처 BFF 프록시(`/api/[...path]`)를 거쳐 글 상세를 가져온다.
 * 없거나 지운 글은 404 `POST_NOT_FOUND`를 `ApiError`로 던진다.
 */
export async function fetchPostDetail(
  postId: number,
  fetchImpl: typeof fetch = fetch,
): Promise<PostDetail> {
  const response = await fetchImpl(`/api/posts/${postId}`, {
    cache: "no-store",
  });
  const body = (await response.json()) as ApiResponse<PostDetail>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

/**
 * 다시 불러와도 결과가 바뀌지 않는 실패(4xx: 지운 글 404, 형식이 틀린 ID 400 등).
 * 5xx와 네트워크 오류는 잠깐의 장애일 수 있으므로 폴링을 이어 간다. 여기서 멈추면
 * 화면이 "분석 중"에 영영 머문다(US1-AC3).
 */
function isTerminalError(error: unknown): boolean {
  return error instanceof ApiError && error.status >= 400 && error.status < 500;
}

/**
 * 이 글을 지우는 뮤테이션(`MUTATION_KEYS.deletePost`)이 진행 중이거나 성공했는지.
 * 지우는 동안이나 지운 직후 화면을 떠나기 전에 폴링이 돌면 404("삭제된 글이에요")가 깜박인다.
 */
function useDeletingOrDeleted(postId: number): boolean {
  const statuses = useMutationState({
    filters: { mutationKey: MUTATION_KEYS.deletePost(postId), exact: true },
    select: (mutation) => mutation.state.status,
  });
  return statuses.some(
    (status) => status === "pending" || status === "success",
  );
}

/**
 * 글 상세. 몬스터가 생길 때까지 R10 간격으로 다시 불러온다(FR-015, US1-AC3).
 * 이 글을 지우고 있거나 지운 뒤에는 폴링하지 않는다. 쿼리 자체는 끄지 않는다. 성공한 삭제
 * 뮤테이션은 gcTime(5분) 동안 캐시에 남으므로, 끄면 그동안 같은 글을 다시 열 때 데이터 없이
 * 멈춘 쿼리가 되어 "삭제된 글이에요" 대신 로딩만 보인다.
 * 글 고치기 화면처럼 몬스터를 보여 주지 않는 곳은 `{ poll: false }`로 폴링을 끈다.
 */
export function usePostDetailQuery(
  postId: number,
  { poll = true }: { poll?: boolean } = {},
) {
  const [startedAt] = useState(() => Date.now());
  const deleting = useDeletingOrDeleted(postId);

  return useQuery({
    queryKey: QUERY_KEYS.postDetail(postId),
    queryFn: () => fetchPostDetail(postId),
    refetchInterval: (query) =>
      !poll || deleting || isTerminalError(query.state.error)
        ? false
        : analysisPollInterval(query.state.data, Date.now() - startedAt),
  });
}
