"use client";

import { useEffect, useRef, useState } from "react";

import { ApiError } from "@/shared/api";
import { Button } from "@/shared/ui";

import {
  useRequestReviewMutation,
  type ReviewRequestInput,
} from "../api/use-request-review-mutation";

/**
 * 숨겨진 내 글이나 댓글을 다시 살펴봐 달라고 요청하는 버튼(005 US4-AC8). 숨겨진 내 것에만 둔다.
 *
 * - 대상마다 한 번만 요청할 수 있다. 요청하면 버튼 자리가 안내로 바뀌고 다시 누를 수 없다.
 *   안내 자리는 처음부터 있어 화면 낭독기가 바뀐 글자를 읽고, 초점도 안내로 옮긴다.
 * - 이미 요청했다는 응답(409)도 같은 안내로 바꾼다. 다른 탭에서 먼저 요청한 경우다.
 * - 결과는 알림으로 온다. 풀리면 글이 다시 보이고, 유지되면 살펴봤다는 알림만 온다.
 */
export function RequestReviewButton({
  targetType,
  targetId,
  requested,
  onRequested,
}: ReviewRequestInput & {
  /** 서버가 알려 준 요청 여부(`safety.reviewRequested`). */
  requested: boolean;
  /** 접수된 뒤 부르는 쪽이 자기 캐시를 맞출 때 쓴다. */
  onRequested?: () => void;
}) {
  const review = useRequestReviewMutation();
  const [done, setDone] = useState(false);
  const [failed, setFailed] = useState(false);
  const statusRef = useRef<HTMLParagraphElement>(null);
  const finished = requested || done;

  // 버튼이 사라지면 초점이 문서 처음으로 떨어진다. 방금 접수됐으면 초점을 안내로 옮긴다.
  useEffect(() => {
    if (done) {
      statusRef.current?.focus();
    }
  }, [done]);

  const finish = () => {
    setDone(true);
    onRequested?.();
  };

  return (
    <div className="flex flex-col items-start gap-1">
      {finished ? null : (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          // disabled로 잠그면 초점을 잃는다. 누를 수 없다는 것만 알리고 한 번 더 보내지 않는다.
          aria-disabled={review.isPending || undefined}
          onClick={() => {
            if (review.isPending) {
              return;
            }
            setFailed(false);
            review.mutate(
              { targetType, targetId },
              {
                onSuccess: finish,
                onError: (cause) => {
                  if (
                    cause instanceof ApiError &&
                    cause.code === "REVIEW_ALREADY_REQUESTED"
                  ) {
                    finish();
                    return;
                  }
                  setFailed(true);
                },
              },
            );
          }}
          className="text-label text-fg px-2 font-medium underline underline-offset-2"
        >
          다시 살펴봐 달라고 요청하기
        </Button>
      )}
      {/* 내용이 든 채로 새로 생긴 status는 읽히지 않는 일이 많다. 빈 채로 먼저 그려 두고 글자만 채운다. */}
      <p
        ref={statusRef}
        role="status"
        tabIndex={-1}
        className={finished ? "text-body-sm text-fg-muted" : "sr-only"}
      >
        {finished
          ? "다시 살펴봐 달라고 요청했어요. 결과는 알림으로 알려 드려요."
          : null}
      </p>
      {failed && !finished ? (
        <p role="alert" className="text-caption text-danger">
          요청하지 못했어요. 잠시 후 다시 시도해 주세요.
        </p>
      ) : null}
    </div>
  );
}
