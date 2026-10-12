"use client";

import { Heart } from "lucide-react";

import type { Comment } from "@/entities/comment";
import { cn } from "@/shared/lib";
import { ReactionIcon } from "@/shared/ui";

import { useCommentLikeMutation } from "../api/use-comment-like-mutation";
import { likeErrorMessage } from "../model/like-error";
import { useEverLiked } from "../model/use-ever-liked";
import { useLikeFeedback } from "../model/use-like-feedback";

/**
 * 댓글과 답글 공감 버튼(US3-AC4, US3-AC6). 내 댓글에도 공감할 수 있다. 글 작성자의
 * 공감이면 HP는 줄지 않는다(US3-AC8, 낙관적 계산이 상세의 `mine`으로 가린다).
 */
export function CommentLikeButton({
  postId,
  comment,
}: {
  postId: number;
  comment: Comment;
}) {
  const mutation = useCommentLikeMutation(postId);
  const everLiked = useEverLiked(comment.likedByMe);
  const feedback = useLikeFeedback();
  const liked = comment.likedByMe;
  const errorMessage = likeErrorMessage(mutation.error);

  return (
    <>
      <button
        type="button"
        aria-pressed={liked}
        aria-label={`댓글 공감 ${comment.likeCount}`}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        onClick={() => {
          if (mutation.isPending || !feedback.begin()) return;
          mutation.mutate(
            {
              commentId: comment.commentId,
              like: !liked,
              alreadyApplied: everLiked,
            },
            {
              onSuccess: (result) => feedback.success(result.likedByMe),
              onSettled: feedback.settle,
            },
          );
        }}
        className={cn(
          "focus-visible:ring-brand-600 inline-flex min-h-11 items-center gap-1.5 rounded-lg px-3 py-1 tabular-nums transition-colors focus-visible:ring-2 focus-visible:outline-none disabled:opacity-50",
          liked
            ? "bg-brand-50 text-brand-700"
            : "text-neutral-600 hover:bg-neutral-100",
        )}
      >
        <ReactionIcon confirmation={feedback.confirmation}>
          <Heart
            size={14}
            aria-hidden
            className={liked ? "fill-brand-100" : ""}
          />
        </ReactionIcon>
        공감 {comment.likeCount}
      </button>
      <span role="status" className="sr-only">
        {mutation.isPending ? "공감 처리 중" : feedback.message}
      </span>
      {errorMessage ? (
        <span role="alert" className="text-red-600">
          {errorMessage}
        </span>
      ) : null}
    </>
  );
}
