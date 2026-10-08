"use client";

import { useQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { EmotionStats } from "../model/types";

/** 같은 출처 BFF 프록시를 거쳐 내 감정 통계를 가져온다. */
export function fetchEmotionStats(
  fetchImpl: typeof fetch = fetch,
): Promise<EmotionStats> {
  return requestApi<EmotionStats>(
    "/api/members/me/emotion-stats",
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 마이페이지 감정 통계(004 US4). 글쓰기, 처치, 공격이 다른 화면에서 일어나므로 마이페이지를 열 때마다
 * 다시 받는다.
 */
export function useEmotionStatsQuery() {
  return useQuery({
    queryKey: QUERY_KEYS.emotionStats,
    queryFn: () => fetchEmotionStats(),
    refetchOnMount: "always",
  });
}
