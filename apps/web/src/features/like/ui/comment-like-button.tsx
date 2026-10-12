"use client";

import { Heart } from "@phosphor-icons/react";

import type { Comment } from "@/entities/comment";
import { cn } from "@/shared/lib";
import { Button, ReactionIcon } from "@/shared/ui";

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
      <Button
        type="button"
        variant="ghost"
        size="sm"
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
          "text-label gap-1.5 px-2 font-medium tabular-nums",
          liked && "bg-surface-sunken text-fg",
        )}
      >
        <ReactionIcon confirmation={feedback.confirmation}>
          <Heart size={16} weight={liked ? "fill" : "regular"} aria-hidden />
        </ReactionIcon>
        공감 {comment.likeCount}
      </Button>
      <span role="status" className="sr-only">
        {mutation.isPending ? "공감 처리 중" : feedback.message}
      </span>
      {errorMessage ? (
        <span role="alert" className="text-danger">
          {errorMessage}
        </span>
      ) : null}
    </>
  );
}
