"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { PostDetail } from "@/entities/post";
import { ApiError } from "@/shared/api";
import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

import { togglePostLike } from "./like-api";
import { attackOptimistically, settleAttack } from "./optimistic-cache";

export interface PostLikeVariables {
  /** true면 공감, false면 취소. */
  like: boolean;
  /** 이 공감이 이미 HP에 반영됐다고 화면이 알면 true(optimistic-hp.ts). */
  alreadyApplied?: boolean;
}

/**
 * 글 공감과 취소(US3-AC1, US3-AC6). 응답 전에 공감 수와 HP를 바꿔 보여 주고
 * (US3-AC10), 실패하면 되돌린다. 이미 공감한 글(409)은 공감한 상태로 둔다. 취소는 HP를
 * 돌려놓지 않는다. 이 글의 마지막 공격이 끝나면 상세만 다시 불러와 서버 값으로 맞춘다.
 */
function isAlreadyLiked(error: unknown): boolean {
  return error instanceof ApiError && error.code === "ALREADY_LIKED";
}

export function usePostLikeMutation(postId: number) {
  const queryClient = useQueryClient();
  const key = QUERY_KEYS.postDetail(postId);

  return useMutation({
    mutationKey: MUTATION_KEYS.attack(postId),
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
    onError: (error, _variables, context) => {
      if (!context) return;
      // 낙관적 HP는 언제나 되돌린다. 409면 서버가 이 공감을 이미 반영했으므로 이번에 또
      // 줄인 값이 틀렸다. 되돌리기는 그 사이 다른 값이 들어왔으면 건드리지 않는다.
      context.rollbackHp();
      // 이미 공감한 상태(409 ALREADY_LIKED)는 바라던 결과와 같으므로 공감 상태는 그대로 둔다.
      if (isAlreadyLiked(error)) return;
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
    onSettled: () =>
      settleAttack(queryClient, postId, { refreshComments: false }),
  });
}
