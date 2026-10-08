"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

/** 같은 출처 BFF 프록시를 거쳐 `PATCH /api/v1/comments/{id}`를 부른다(204). */
export function updateComment(
  commentId: number,
  content: string,
): Promise<void> {
  return requestApi<void>(`/api/comments/${commentId}`, {
    method: "PATCH",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ content }),
  });
}

/** 같은 출처 BFF 프록시를 거쳐 `DELETE /api/v1/comments/{id}`를 부른다(204). 원 댓글이면 답글도 지워진다. */
export function deleteComment(commentId: number): Promise<void> {
  return requestApi<void>(`/api/comments/${commentId}`, { method: "DELETE" });
}

/**
 * 댓글을 고치거나 지운 뒤 다시 불러올 것: 댓글 목록(`["posts", id, "comments"]`)과
 * 상세(`["posts", id]`, 지우면 댓글 수가 준다)는 `["posts", id]` 하나로 함께 무효화한다.
 * 피드의 댓글 수도 바뀌므로 피드는 낡은 것으로만 표시한다. HP는 바뀌지 않는다.
 */
function useRefreshAfterCommentChange(postId: number) {
  const queryClient = useQueryClient();
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.postDetail(postId),
      }),
      queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.allFeeds,
        refetchType: "none",
      }),
    ]);
  };
}

/** 댓글 수정(US4-AC3). */
export function useUpdateCommentMutation(postId: number) {
  const refresh = useRefreshAfterCommentChange(postId);
  return useMutation({
    mutationFn: ({
      commentId,
      content,
    }: {
      commentId: number;
      content: string;
    }) => updateComment(commentId, content),
    onSuccess: refresh,
  });
}

/** 댓글 삭제(US4-AC3). 원 댓글이면 서버가 답글도 함께 지운다. */
export function useDeleteCommentMutation(postId: number) {
  const refresh = useRefreshAfterCommentChange(postId);
  return useMutation({
    mutationFn: (commentId: number) => deleteComment(commentId),
    onSuccess: refresh,
  });
}
