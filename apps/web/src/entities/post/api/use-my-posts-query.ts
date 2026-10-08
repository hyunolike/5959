"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { FeedPage } from "../model/types";

/** 같은 출처 BFF 프록시를 거쳐 내가 쓴 글 한 쪽을 가져온다. 항목은 피드와 같다. */
export function fetchMyPostsPage(
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<FeedPage> {
  const query = cursor ? `?${new URLSearchParams({ cursor })}` : "";
  return requestApi<FeedPage>(
    `/api/members/me/posts${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 마이페이지 "내가 쓴 글"(004 US3-AC1). 최신순 키셋으로 20개씩 이어 붙인다. 글을 쓰거나 지우는 일이
 * 다른 화면에서 일어나므로 탭을 열 때마다 다시 받는다.
 */
export function useMyPostsQuery() {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.myPosts,
    queryFn: ({ pageParam }) => fetchMyPostsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
  });
}
