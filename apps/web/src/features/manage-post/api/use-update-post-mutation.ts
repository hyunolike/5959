"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { QUERY_KEYS } from "@/shared/config";

import { updatePost, type PostUpdateRequest } from "./manage-post-api";

/**
 * 글 수정(US4-AC1). 성공하면 상세(`["posts", id]`)와 모든 피드(`["feed"]`, 미리보기 본문)를
 * 무효화한다. 몬스터는 바뀌지 않는다.
 */
export function useUpdatePostMutation(postId: number) {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (values: PostUpdateRequest) => updatePost(postId, values),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: QUERY_KEYS.postDetail(postId),
        }),
        queryClient.invalidateQueries({ queryKey: QUERY_KEYS.allFeeds }),
      ]);
    },
  });
}
