"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { PostDetail } from "@/entities/post";
import { QUERY_KEYS } from "@/shared/config";

import { togglePostLike } from "./like-api";
import { attackOptimistically, refreshAfterAttack } from "./optimistic-cache";

export interface PostLikeVariables {
  /** true면 공감, false면 취소. */
  like: boolean;
  /** 이 공감이 이미 HP에 반영됐다고 화면이 알면 true(optimistic-hp.ts). */
  alreadyApplied?: boolean;
}

/**
 * 글 공감과 취소(US3-AC1, US3-AC6). 응답 전에 공감 수와 HP를 바꿔 보여 주고
 * (US3-AC10), 실패하면 되돌린다. 취소는 HP를 돌려놓지 않는다. 끝나면 상세를
 * 다시 불러와 서버 값으로 맞춘다.
 */
export function usePostLikeMutation(postId: number) {
  const queryClient = useQueryClient();
  const key = QUERY_KEYS.postDetail(postId);

  return useMutation({
    mutationFn: ({ like }: PostLikeVariables) => togglePostLike(postId, like),
    onMutate: async ({ like, alreadyApplied }) => {
      // 분석 중 폴링이 응답 전에 돌아와 낙관적 값을 덮지 않게 한다.
      await queryClient.cancelQueries({ queryKey: key, exact: true });
      const before = queryClient.getQueryData<PostDetail>(key);
      if (before && before.likedByMe !== like) {
        queryClient.setQueryData<PostDetail>(key, {
          ...before,
          likedByMe: like,
          likeCount: Math.max(0, before.likeCount + (like ? 1 : -1)),
        });
      }
      const rollbackHp = like
        ? attackOptimistically(queryClient, postId, "POST_LIKE", {
            alreadyApplied,
          })
        : () => {};
      return { before, rollbackHp };
    },
    onError: (_error, _variables, context) => {
      if (!context) return;
      context.rollbackHp();
      const { before } = context;
      if (before) {
        queryClient.setQueryData<PostDetail>(key, (current) =>
          current
            ? {
                ...current,
                likedByMe: before.likedByMe,
                likeCount: before.likeCount,
              }
            : current,
        );
      }
    },
    onSettled: () => refreshAfterAttack(queryClient, postId),
  });
}
