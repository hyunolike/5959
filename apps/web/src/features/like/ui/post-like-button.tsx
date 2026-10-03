"use client";

import type { PostDetail } from "@/entities/post";
import { cn } from "@/shared/lib";
import { Button } from "@/shared/ui";

import { usePostLikeMutation } from "../api/use-post-like-mutation";
import { likeErrorMessage } from "../model/like-error";
import { useEverLiked } from "../model/use-ever-liked";

/**
 * 글 공감 버튼(US3-AC1, US3-AC6). 누를 때마다 공감과 취소가 바뀐다.
 * 작성자는 자기 글에 공감할 수 없으므로 버튼을 그리지 않는다(US3-AC8).
 */
export function PostLikeButton({ detail }: { detail: PostDetail }) {
  const mutation = usePostLikeMutation(detail.postId);
  const everLiked = useEverLiked(detail.likedByMe);

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
        variant={liked ? "primary" : "outline"}
        aria-pressed={liked}
        disabled={mutation.isPending}
        className={cn("tabular-nums", liked && "bg-red-600 hover:bg-red-500")}
        onClick={() =>
          mutation.mutate({ like: !liked, alreadyApplied: everLiked })
        }
      >
        공감 {detail.likeCount}
      </Button>
      {errorMessage ? (
        <p role="alert" className="text-xs text-red-600">
          {errorMessage}
        </p>
      ) : null}
    </div>
  );
}
