"use client";

import { useMutation } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";

import type { ReportReason, ReportTargetType } from "../model/reason";

export interface ReportInput {
  targetType: ReportTargetType;
  targetId: number;
  reason: ReportReason;
  /** 사유가 기타일 때만 보낸다. */
  detail?: string;
}

/**
 * 같은 출처 BFF 프록시를 거친 신고. 접수되면 204다. 이미 신고했으면 409 `ALREADY_REPORTED`, 한 시간 한도를
 * 넘으면 429 `REPORT_RATE_LIMITED`를 `ApiError`로 던진다.
 */
export function reportContent(
  input: ReportInput,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  return requestApi<void>(
    "/api/reports",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(input),
    },
    fetchImpl,
  );
}

/** 신고(005 US3). 신고는 화면의 어떤 목록도 바꾸지 않으므로 캐시를 건드리지 않는다. */
export function useReportMutation() {
  return useMutation({
    mutationFn: (input: ReportInput) => reportContent(input),
  });
}
