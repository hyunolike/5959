"use client";

import { useMutation } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";

export interface ReviewRequestInput {
  targetType: "POST" | "COMMENT";
  targetId: number;
}

/**
 * 같은 출처 BFF 프록시를 거친 재검토 요청. 접수되면 204다. 이미 요청했으면 409
 * `REVIEW_ALREADY_REQUESTED`를 `ApiError`로 던진다.
 */
export function requestReview(
  input: ReviewRequestInput,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  return requestApi<void>(
    "/api/review-requests",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(input),
    },
    fetchImpl,
  );
}

/** 숨겨진 내 글이나 댓글의 재검토 요청(005 US4-AC8). 대상마다 한 번만 할 수 있다. */
export function useRequestReviewMutation() {
  return useMutation({
    mutationFn: (input: ReviewRequestInput) => requestReview(input),
  });
}
