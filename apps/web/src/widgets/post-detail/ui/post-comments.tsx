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
import { ReportButton } from "@/features/report-content";
import { RequestReviewButton } from "@/features/request-review";
import { QUERY_KEYS } from "@/shared/config";
import { Button, Spinner } from "@/shared/ui";

import { SafetyNotice, type ContentSafety } from "./safety-notice";

const LEVEL_ORDER = { NONE: 0, CONCERN: 1, CRISIS: 2 } as const;

/**
 * 이 화면에 보이는 내 댓글과 답글의 `safety` 가운데 가장 무거운 것. 숨겨진 것이 하나라도 있으면 숨김으로 본다.
 * 안내는 댓글마다 따로 띄우지 않고 목록 아래에 하나만 둔다.
 */
export function worstSafetyOfMine(
  comments: readonly Comment[],
): ContentSafety | undefined {
  return comments
    .flatMap((comment) => [comment, ...comment.replies])
    .map((comment) => comment.safety)
    .filter((safety) => safety !== undefined)
    .reduce<ContentSafety | undefined>((worst, safety) => {
      if (worst === undefined) {
        return safety;
      }
      return {
        level:
          LEVEL_ORDER[safety.level] > LEVEL_ORDER[worst.level]
            ? safety.level
            : worst.level,
        hidden: worst.hidden || safety.hidden,
        reviewRequested: worst.reviewRequested && safety.reviewRequested,
      };
    }, undefined);
}

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
  const mySafety = worstSafetyOfMine(comments);

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
                      <>
                        <CommentManageMenu
                          postId={postId}
                          comment={target}
                          focusAfterDelete={() => sectionRef.current}
                        />
                        {/* 숨겨진 내 댓글은 다시 살펴봐 달라고 한 번 요청할 수 있다(005 US4-AC8). */}
                        {target.hidden ? (
                          <RequestReviewButton
                            targetType="COMMENT"
                            targetId={target.commentId}
                            requested={target.safety?.reviewRequested ?? false}
                            onRequested={() => {
                              void queryClient.invalidateQueries({
                                queryKey: QUERY_KEYS.comments(postId),
                              });
                            }}
                          />
                        ) : null}
                      </>
                    ) : (
                      // 다른 회원의 댓글에만 신고를 둔다(005 US3-AC3).
                      <ReportButton
                        targetType="COMMENT"
                        targetId={target.commentId}
                      />
                    )}
                  </>
                )}
              />
            </li>
          ))}
        </ul>
      )}

      {/* 내 댓글이 우려나 위기로 판정됐을 때의 도움 안내(005 US1-AC5). */}
      <SafetyNotice safety={mySafety} target="comment" />

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
