"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { CommentPage } from "../model/types";

export function commentsRequestPath(
  postId: number,
  cursor: string | null,
): string {
  const base = `/api/posts/${postId}/comments`;
  return cursor ? `${base}?${new URLSearchParams({ cursor })}` : base;
}

/** 같은 출처 BFF 프록시를 거쳐 댓글 한 쪽(원 댓글 50개와 그 답글)을 가져온다. */
export function fetchCommentsPage(
  postId: number,
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<CommentPage> {
  return requestApi<CommentPage>(
    commentsRequestPath(postId, cursor),
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 글의 댓글 목록(FR-012). 원 댓글은 오래된 순으로 50개씩 이어 불러오고, 답글은 각 원 댓글
 * 아래에 모두 담겨 온다. 키는 상세 아래(`["posts", id, "comments"]`)라 상세를 무효화하면
 * 함께 다시 불러온다(shared/config/constants.ts).
 */
export function useCommentsQuery(postId: number) {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.comments(postId),
    queryFn: ({ pageParam }) => fetchCommentsPage(postId, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}
