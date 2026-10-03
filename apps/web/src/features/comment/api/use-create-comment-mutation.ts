"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { Comment } from "@/entities/comment";
import { requestApi } from "@/shared/api";
import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

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
 * 댓글과 답글 쓰기(US3-AC2, US3-AC3). 공격 규칙과 겹친 공격의 새로고침은 features/like가
 * 맡으므로 위젯이 넘긴다.
 * - `onOptimisticAttack`: 응답 전에 첫 댓글의 HP −3을 반영하고 되돌리는 함수를 돌려준다.
 * - `onAttackSettled`: 끝나면(성공이든 실패든) 부른다. 이 글의 마지막 공격일 때만 상세와
 *   댓글 목록을 다시 불러온다.
 *
 * 다른 공격과 같은 뮤테이션 키(`["attack", postId]`)를 써서 겹친 공격으로 함께 센다.
 * `onAttackSettled`가 없으면 마지막 공격일 때 `["posts", id]` 아래를 직접 다시 불러온다.
 */
export function useCreateCommentMutation(
  postId: number,
  {
    onOptimisticAttack,
    onAttackSettled,
  }: {
    onOptimisticAttack?: () => () => void;
    onAttackSettled?: () => void;
  } = {},
) {
  const queryClient = useQueryClient();
  const detailKey = QUERY_KEYS.postDetail(postId);
  const mutationKey = MUTATION_KEYS.attack(postId);

  return useMutation({
    mutationKey,
    mutationFn: (variables: CreateCommentVariables) =>
      createComment(postId, variables),
    onMutate: async () => {
      // 분석 중 폴링이 응답 전에 돌아와 낙관적 HP를 덮지 않게 한다.
      await queryClient.cancelQueries({ queryKey: detailKey, exact: true });
      return { rollback: onOptimisticAttack?.() ?? noop };
    },
    onError: (_error, _variables, context) => context?.rollback(),
    onSettled: () => {
      if (onAttackSettled) {
        onAttackSettled();
        return;
      }
      if (queryClient.isMutating({ mutationKey }) === 1) {
        void queryClient.invalidateQueries({ queryKey: detailKey });
      }
    },
  });
}
