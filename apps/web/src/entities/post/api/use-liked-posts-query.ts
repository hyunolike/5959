"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { FeedPage } from "../model/types";

/** 같은 출처 BFF 프록시를 거쳐 공감한 글 한 쪽을 가져온다. 항목은 피드와 같다. */
export function fetchLikedPostsPage(
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<FeedPage> {
  const query = cursor ? `?${new URLSearchParams({ cursor })}` : "";
  return requestApi<FeedPage>(
    `/api/members/me/liked-posts${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 마이페이지 "공감한 글"(004 US3-AC3). 공감한 시각의 최신순 키셋으로 20개씩 이어 붙인다. 공감과 취소가
 * 다른 화면에서 일어나므로 탭을 열 때마다 다시 받는다.
 */
export function useLikedPostsQuery() {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.likedPosts,
    queryFn: ({ pageParam }) => fetchLikedPostsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
  });
}
