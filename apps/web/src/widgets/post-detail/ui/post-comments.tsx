"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";

import {
  CommentItem,
  useCommentsQuery,
  type Comment,
} from "@/entities/comment";
import { CommentManageMenu, WriteCommentForm } from "@/features/comment";
import {
  attackOptimistically,
  CommentLikeButton,
  settleAttack,
} from "@/features/like";
import { Button, Spinner } from "@/shared/ui";

/**
 * 글의 댓글 영역(FR-012, US3-AC2~AC4): 원 댓글과 답글 목록, 댓글 공감, 답글 달기,
 * 댓글 쓰기. 원 댓글은 50개씩 이어 불러온다. 답글에는 다시 답글을 달 수 없으므로
 * 답글 달기 버튼은 원 댓글에만 둔다. 수정과 삭제 메뉴는 내 댓글(`mine`)에만 둔다(US4-AC3, AC4).
 */
export function PostComments({ postId }: { postId: number }) {
  const queryClient = useQueryClient();
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  // 내 댓글을 지우면 그 메뉴가 사라지므로 초점을 댓글 목록 영역으로 옮긴다.
  const sectionRef = useRef<HTMLElement>(null);
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
    <section
      ref={sectionRef}
      tabIndex={-1}
      aria-label="댓글 목록"
      className="flex flex-col gap-4 focus-visible:outline-none"
    >
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
                    {target.mine ? (
                      <CommentManageMenu
                        postId={postId}
                        comment={target}
                        focusAfterDelete={() => sectionRef.current}
                      />
                    ) : null}
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
        onAttackSettled={() =>
          settleAttack(queryClient, postId, { refreshComments: true })
        }
      />
    </section>
  );
}
