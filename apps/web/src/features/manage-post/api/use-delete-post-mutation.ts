"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { QUERY_KEYS } from "@/shared/config";

import { deletePost } from "./manage-post-api";

/**
 * 글 삭제(US4-AC2). 성공하면 상세와 그 아래 댓글 캐시(`["posts", id]` 아래)를 지우고,
 * 피드(`["feed"]`)는 낡은 것으로 표시해 돌아갔을 때 지운 글이 빠진 목록을 다시 불러온다.
 */
export function useDeletePostMutation(postId: number) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: () => deletePost(postId),
    onSuccess: async () => {
      queryClient.removeQueries({ queryKey: QUERY_KEYS.postDetail(postId) });
      await queryClient.invalidateQueries({ queryKey: QUERY_KEYS.allFeeds });
    },
  });
}
