"use client";

import { useQuery } from "@tanstack/react-query";

import { requestApi, type components } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

export type WithdrawalNotice = components["schemas"]["WithdrawalNotice"];

/** 같은 출처 BFF 프록시를 거쳐, 탈퇴 화면에 도움받을 곳을 보일지 묻는다. */
export function fetchWithdrawalNotice(
  fetchImpl: typeof fetch = fetch,
): Promise<WithdrawalNotice> {
  return requestApi<WithdrawalNotice>(
    "/api/safety/withdrawal-notice",
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 탈퇴 화면에 도움받을 곳을 함께 보일지(013 US1-AC10). 최근에 위기로 판정된 글이나 댓글이 있던
 * 회원에게만 true다. 받지 못하면 안내 없이 화면을 그린다. 탈퇴를 막는 데 쓰지 않는다(FR-019).
 * 온보딩 전 회원은 글이 없고 API도 403이라 [enabled]를 false로 부른다.
 */
export function useWithdrawalNoticeQuery({ enabled = true } = {}) {
  return useQuery({
    queryKey: QUERY_KEYS.withdrawalNotice,
    queryFn: () => fetchWithdrawalNotice(),
    enabled,
    retry: false,
  });
}
