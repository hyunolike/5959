"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { Comment } from "@/entities/comment";
import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

export interface CreateCommentVariables {
  content: string;
  /** 답글이면 원 댓글 ID. 답글에 다시 답글을 달면 400 `INVALID_PARENT_COMMENT`다. */
  parentId?: number;
}

/**
 * 같은 출처 BFF 프록시를 거쳐 `POST /api/v1/posts/{id}/comments`를 부른다.
 * 응답에는 HP가 없다(research R6).
 */
export function createComment(
  postId: number,
  variables: CreateCommentVariables,
): Promise<Comment> {
  return requestApi<Comment>(`/api/posts/${postId}/comments`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(variables),
  });
}

const noop = () => {};

/**
 * 댓글과 답글 쓰기(US3-AC2, US3-AC3). 첫 댓글의 HP −3은 공격 규칙을 아는
 * features/like가 맡으므로, 위젯이 `onOptimisticAttack`으로 넘겨 응답 전에 반영하고
 * 되돌리는 함수를 받는다. 끝나면 상세와 댓글 목록(`["posts", id]` 아래 전부)을 다시
 * 불러와 서버 값으로 맞추고, 피드는 낡은 것으로 표시한다.
 */
export function useCreateCommentMutation(
  postId: number,
  onOptimisticAttack?: () => () => void,
) {
  const queryClient = useQueryClient();
  const detailKey = QUERY_KEYS.postDetail(postId);

  return useMutation({
    mutationFn: (variables: CreateCommentVariables) =>
      createComment(postId, variables),
    onMutate: async () => {
      // 분석 중 폴링이 응답 전에 돌아와 낙관적 HP를 덮지 않게 한다.
      await queryClient.cancelQueries({ queryKey: detailKey, exact: true });
      return { rollback: onOptimisticAttack?.() ?? noop };
    },
    onError: (_error, _variables, context) => context?.rollback(),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: detailKey });
      void queryClient.invalidateQueries({
        queryKey: QUERY_KEYS.allFeeds,
        refetchType: "none",
      });
    },
  });
}
