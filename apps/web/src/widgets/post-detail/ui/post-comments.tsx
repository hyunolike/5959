"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  CommentItem,
  useCommentsQuery,
  type Comment,
} from "@/entities/comment";
import { WriteCommentForm } from "@/features/comment";
import { attackOptimistically, CommentLikeButton } from "@/features/like";
import { Button, Spinner } from "@/shared/ui";

/**
 * 글의 댓글 영역(FR-012, US3-AC2~AC4): 원 댓글과 답글 목록, 댓글 공감, 답글 달기,
 * 댓글 쓰기. 원 댓글은 50개씩 이어 불러온다. 답글에는 다시 답글을 달 수 없으므로
 * 답글 달기 버튼은 원 댓글에만 둔다.
 */
export function PostComments({ postId }: { postId: number }) {
  const queryClient = useQueryClient();
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  const {
    data,
    isPending,
    isError,
    hasNextPage,
    isFetchingNextPage,
    fetchNextPage,
  } = useCommentsQuery(postId);
  const comments = data?.pages.flatMap((page) => page.items) ?? [];

  return (
    <section aria-label="댓글 목록" className="flex flex-col gap-4">
      {isPending ? (
        <div className="flex justify-center py-2">
          <Spinner />
        </div>
      ) : isError ? (
        <p role="alert" className="text-sm text-red-600">
          댓글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      ) : comments.length === 0 ? (
        <p className="text-sm text-neutral-500">첫 댓글을 남겨 주세요.</p>
      ) : (
        <ul className="flex flex-col gap-4">
          {comments.map((comment) => (
            <li key={comment.commentId}>
              <CommentItem
                comment={comment}
                renderActions={(target, { isReply }) => (
                  <>
                    <CommentLikeButton postId={postId} comment={target} />
                    {isReply ? null : (
                      <button
                        type="button"
                        onClick={() => setReplyTo(target)}
                        className="rounded px-1 text-neutral-500 hover:text-neutral-900 focus-visible:ring-2 focus-visible:ring-neutral-950 focus-visible:outline-none"
                      >
                        답글 달기
                      </button>
                    )}
                  </>
                )}
              />
            </li>
          ))}
        </ul>
      )}

      {hasNextPage ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          disabled={isFetchingNextPage}
          onClick={() => void fetchNextPage({ cancelRefetch: false })}
        >
          댓글 더 보기
        </Button>
      ) : null}

      <WriteCommentForm
        postId={postId}
        replyTo={replyTo}
        onReplyDone={() => setReplyTo(null)}
        onOptimisticAttack={() =>
          attackOptimistically(queryClient, postId, "COMMENT")
        }
      />
    </section>
  );
}
