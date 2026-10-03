"use client";

import type { Comment } from "@/entities/comment";
import { cn } from "@/shared/lib";

import { useCommentLikeMutation } from "../api/use-comment-like-mutation";
import { likeErrorMessage } from "../model/like-error";
import { useEverLiked } from "../model/use-ever-liked";

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
  const liked = comment.likedByMe;
  const errorMessage = likeErrorMessage(mutation.error);

  return (
    <>
      <button
        type="button"
        aria-pressed={liked}
        aria-label={`댓글 공감 ${comment.likeCount}`}
        disabled={mutation.isPending}
        onClick={() =>
          mutation.mutate({
            commentId: comment.commentId,
            like: !liked,
            alreadyApplied: everLiked,
          })
        }
        className={cn(
          "rounded-full px-2 py-0.5 tabular-nums transition-colors focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none disabled:opacity-50",
          liked
            ? "bg-red-50 text-red-600"
            : "text-neutral-600 hover:bg-neutral-100",
        )}
      >
        공감 {comment.likeCount}
      </button>
      {errorMessage ? (
        <span role="alert" className="text-red-600">
          {errorMessage}
        </span>
      ) : null}
    </>
  );
}
