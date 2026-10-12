"use client";

import type { PostDetail } from "@/entities/post";
import { Heart } from "lucide-react";
import { Button, ReactionIcon } from "@/shared/ui";

import { usePostLikeMutation } from "../api/use-post-like-mutation";
import { likeErrorMessage } from "../model/like-error";
import { useEverLiked } from "../model/use-ever-liked";
import { useLikeFeedback } from "../model/use-like-feedback";

/**
 * 글 공감 버튼(US3-AC1, US3-AC6). 누를 때마다 공감과 취소가 바뀐다.
 * 작성자는 자기 글에 공감할 수 없으므로 버튼을 그리지 않는다(US3-AC8).
 */
export function PostLikeButton({ detail }: { detail: PostDetail }) {
  const mutation = usePostLikeMutation(detail.postId);
  const everLiked = useEverLiked(detail.likedByMe);
  const feedback = useLikeFeedback();

  if (detail.mine) {
    return null;
  }

  const liked = detail.likedByMe;
  const errorMessage = likeErrorMessage(mutation.error);

  return (
    <div className="flex items-center gap-2">
      <Button
        type="button"
        size="sm"
        variant={liked ? "primary" : "secondary"}
        aria-pressed={liked}
        disabled={mutation.isPending}
        aria-busy={mutation.isPending}
        className="min-h-11 tabular-nums"
        onClick={() => {
          if (mutation.isPending || !feedback.begin()) return;
          mutation.mutate(
            { like: !liked, alreadyApplied: everLiked },
            {
              onSuccess: (result) => feedback.success(result.likedByMe),
              onSettled: feedback.settle,
            },
          );
        }}
      >
        <ReactionIcon confirmation={feedback.confirmation}>
          <Heart
            size={16}
            aria-hidden
            className={liked ? "fill-current" : ""}
          />
        </ReactionIcon>
        공감 {detail.likeCount}
      </Button>
      <span role="status" className="sr-only">
        {mutation.isPending ? "공감 처리 중" : feedback.message}
      </span>
      {errorMessage ? (
        <p role="alert" className="text-xs text-red-600">
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}
