"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";

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
/** 아래에 붙은 댓글 입력줄의 높이. `globals.css`와 화면 틀, 토스트가 읽는다. */
const COMMENT_DOCK_HEIGHT = "--comment-dock-height";

export function PostComments({ postId }: { postId: number }) {
  const queryClient = useQueryClient();
  const [replyTo, setReplyTo] = useState<Comment | null>(null);
  // 내 댓글을 지우면 그 메뉴가 사라지므로 초점을 댓글 목록 영역으로 옮긴다.
  const sectionRef = useRef<HTMLElement>(null);
  // 좁은 화면에서 아래에 붙는 입력줄은 답글 대상, 오류, 여러 줄 입력으로 높아진다. 그 높이를 CSS 변수로
  // 알려, 본문 아래의 여백(화면 틀)과 스크롤 여백, 토스트의 자리가 따라가게 한다.
  const dockRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const dock = dockRef.current;
    if (!dock || typeof ResizeObserver === "undefined") {
      return;
    }
    const root = document.documentElement;
    const observer = new ResizeObserver((entries) => {
      const target = entries[0]?.target as HTMLElement | undefined;
      if (target) {
        root.style.setProperty(COMMENT_DOCK_HEIGHT, `${target.offsetHeight}px`);
      }
    });
    observer.observe(dock);
    return () => {
      observer.disconnect();
      root.style.removeProperty(COMMENT_DOCK_HEIGHT);
    };
  }, []);
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
      // 내 댓글을 지우면 초점이 여기로 온다. 목록 전체에 외곽선을 두르지 않게 공통 초점 표시를 끈다.
      className="flex flex-col gap-4 focus-visible:outline-none"
    >
      {isPending ? (
        <div className="flex justify-center py-2">
          <Spinner />
        </div>
      ) : isError ? (
        <p role="alert" className="text-body-sm text-danger">
          댓글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      ) : comments.length === 0 ? (
        <p className="text-body-sm text-fg-subtle">첫 댓글을 남겨 주세요.</p>
      ) : (
        <ul className="divide-line flex flex-col divide-y [&>li]:py-4 [&>li:first-child]:pt-0">
          {comments.map((comment) => (
            <li key={comment.commentId}>
              <CommentItem
                comment={comment}
                renderActions={(target, { isReply }) => (
                  <>
                    <CommentLikeButton postId={postId} comment={target} />
                    {isReply ? null : (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="text-label px-2 font-medium"
                        onClick={() => setReplyTo(target)}
                      >
                        답글 달기
                      </Button>
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

      {/*
        좁은 화면에서는 입력줄을 화면 아래에 붙인다. 이 화면에는 하단 메뉴가 없다(화면 틀이 숨긴다).
        입력줄의 높이를 위의 변수로 알려 두어, 화면 틀이 본문 아래를 그만큼 비운다.
        넓은 화면에서는 제자리(댓글 목록 아래)에 둔다.
      */}
      <div
        ref={dockRef}
        data-comment-dock
        className="border-line bg-surface fixed inset-x-0 bottom-0 z-30 border-t px-5 pt-2.5 pb-[max(0.625rem,env(safe-area-inset-bottom))] sm:px-8 md:static md:z-auto md:border-t-0 md:bg-transparent md:p-0"
      >
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
      </div>
    </section>
  );
}
