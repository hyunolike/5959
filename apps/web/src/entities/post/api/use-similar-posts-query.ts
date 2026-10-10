"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { SimilarPosts } from "../model/types";

/** 007 research R8: 이 글의 추천이 아직 준비 중이면 3초마다 다시 불러온다. */
export const SIMILAR_POLL_MS = 3_000;
/** 30초가 지나면 그만 부른다(US1-AC7). 그 뒤에는 지금 보이는 것이 그대로 남는다. */
export const SIMILAR_POLL_LIMIT_MS = 30_000;

/**
 * 다음 폴링까지의 간격. `false`면 멈춘다. `pending`은 서버가 이 글의 추천을 아직 만들고
 * 있다는 뜻이다. 그동안에도 같은 감정의 글이 먼저 올 수 있어 `items`가 비었는지로 보지 않는다.
 */
export function similarPollInterval(
  data: SimilarPosts | undefined,
  elapsedMs: number,
): number | false {
  return data?.pending && elapsedMs < SIMILAR_POLL_LIMIT_MS
    ? SIMILAR_POLL_MS
    : false;
}

/** 같은 출처 BFF 프록시를 거쳐 이 글과 비슷한 고민을 가져온다. 항목은 피드와 같다. */
export function fetchSimilarPosts(
  postId: number,
  fetchImpl: typeof fetch = fetch,
): Promise<SimilarPosts> {
  return requestApi<SimilarPosts>(
    `/api/posts/${postId}/similar`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 글 상세 아래 비슷한 고민(007 US1). 추천은 덤이라 실패해도 다시 시도하지 않고, 부르는 쪽이
 * 구역을 그리지 않는다(US2-AC7).
 */
export function useSimilarPostsQuery(postId: number) {
  const [startedAt] = useState(() => Date.now());

  return useQuery({
    queryKey: QUERY_KEYS.similarPosts(postId),
    queryFn: () => fetchSimilarPosts(postId),
    retry: false,
    refetchInterval: (query) =>
      query.state.error
        ? false
        : similarPollInterval(query.state.data, Date.now() - startedAt),
  });
}
