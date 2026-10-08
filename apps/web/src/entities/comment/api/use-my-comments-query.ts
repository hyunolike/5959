"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { MyCommentPage } from "../model/types";

/** 같은 출처 BFF 프록시를 거쳐 내 댓글과 답글 한 쪽을 가져온다. */
export function fetchMyCommentsPage(
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<MyCommentPage> {
  const query = cursor ? `?${new URLSearchParams({ cursor })}` : "";
  return requestApi<MyCommentPage>(
    `/api/members/me/comments${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 마이페이지 "내 댓글"(004 US3-AC2). 최신순 키셋으로 20개씩 이어 붙인다. 댓글을 쓰거나 지우는 일이
 * 글 상세에서 일어나므로 탭을 열 때마다 다시 받는다.
 */
export function useMyCommentsQuery() {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.myComments,
    queryFn: ({ pageParam }) => fetchMyCommentsPage(pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
  });
}
