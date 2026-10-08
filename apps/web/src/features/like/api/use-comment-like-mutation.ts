"use client";

import {
  useMutation,
  useQueryClient,
  type InfiniteData,
} from "@tanstack/react-query";

import type { Comment, CommentPage } from "@/entities/comment";
import { ApiError } from "@/shared/api";
import { MUTATION_KEYS, QUERY_KEYS } from "@/shared/config";

import { toggleCommentLike } from "./like-api";
import { attackOptimistically, settleAttack } from "./optimistic-cache";

export interface CommentLikeVariables {
  commentId: number;
  /** true면 공감, false면 취소. */
  like: boolean;
  /** 이 공감이 이미 HP에 반영됐다고 화면이 알면 true(optimistic-hp.ts). */
  alreadyApplied?: boolean;
}

type CommentPages = InfiniteData<CommentPage>;
type LikeState = Pick<Comment, "likeCount" | "likedByMe">;

/** 원 댓글과 답글을 모두 뒤져 `commentId`인 댓글의 공감 상태를 바꾼다. */
function updateLike(
  pages: CommentPages,
  commentId: number,
  next: (comment: Comment) => LikeState,
): CommentPages {
  const update = (comment: Comment): Comment =>
    comment.commentId === commentId
      ? { ...comment, ...next(comment) }
      : comment.replies.length > 0
        ? { ...comment, replies: comment.replies.map(update) }
        : comment;

  return {
    ...pages,
    pages: pages.pages.map((page) => ({
      ...page,
      items: page.items.map(update),
    })),
  };
}

function findLike(
  pages: CommentPages | undefined,
  commentId: number,
): LikeState | undefined {
  for (const page of pages?.pages ?? []) {
    for (const root of page.items) {
      for (const comment of [root, ...root.replies]) {
        if (comment.commentId === commentId) {
          return { likeCount: comment.likeCount, likedByMe: comment.likedByMe };
        }
      }
    }
  }
  return undefined;
}

/**
 * 댓글과 답글 공감, 취소(US3-AC4, US3-AC6). 응답 전에 그 댓글의 공감 수와 글의 HP를
 * 바꿔 보여 주고, 실패하면 되돌린다. 작성자가 자기 글의 댓글에 공감하면 HP는 그대로다
 * (US3-AC8, 상세의 `mine`으로 가린다). 끝나면 상세와 댓글 목록을 다시 불러온다.
 */
export function useCommentLikeMutation(postId: number) {
  const queryClient = useQueryClient();
  const commentsKey = QUERY_KEYS.comments(postId);

  return useMutation({
    mutationKey: MUTATION_KEYS.attack(postId),
    mutationFn: ({ commentId, like }: CommentLikeVariables) =>
      toggleCommentLike(commentId, like),
    onMutate: async ({ commentId, like, alreadyApplied }) => {
      await Promise.all([
        queryClient.cancelQueries({ queryKey: commentsKey }),
        queryClient.cancelQueries({
          queryKey: QUERY_KEYS.postDetail(postId),
          exact: true,
        }),
      ]);
      const before = findLike(
        queryClient.getQueryData<CommentPages>(commentsKey),
        commentId,
      );
      if (before && before.likedByMe !== like) {
        queryClient.setQueryData<CommentPages>(commentsKey, (pages) =>
          pages
            ? updateLike(pages, commentId, (comment) => ({
                likedByMe: like,
                likeCount: Math.max(0, comment.likeCount + (like ? 1 : -1)),
              }))
            : pages,
        );
      }
      const rollbackHp = like
        ? attackOptimistically(queryClient, postId, "COMMENT_LIKE", {
            alreadyApplied,
          })
        : () => {};
      return { before, rollbackHp };
    },
    onError: (error, { commentId }, context) => {
      if (!context) return;
      context.rollbackHp();
      // 이미 공감한 댓글(409 ALREADY_LIKED)은 바라던 결과와 같으므로 공감 상태는 그대로 둔다.
      if (error instanceof ApiError && error.code === "ALREADY_LIKED") return;
      const { before } = context;
      if (before) {
        queryClient.setQueryData<CommentPages>(commentsKey, (pages) =>
          pages ? updateLike(pages, commentId, () => before) : pages,
        );
      }
    },
    onSettled: () =>
      settleAttack(queryClient, postId, { refreshComments: true }),
  });
}
