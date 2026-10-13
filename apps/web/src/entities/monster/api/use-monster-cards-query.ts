"use client";

import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { MonsterCardPage } from "../model/card";
import type { EmotionType } from "../model/types";

/** 같은 출처 BFF 프록시를 거쳐 내 도감 한 쪽을 가져온다. 최근에 물리친 것부터다. */
export function fetchMonsterCardsPage(
  cursor: string | null,
  emotion: EmotionType | null,
  fetchImpl: typeof fetch = fetch,
): Promise<MonsterCardPage> {
  const params = new URLSearchParams();
  if (cursor) params.set("cursor", cursor);
  if (emotion) params.set("emotion", emotion);
  const query = params.size > 0 ? `?${params}` : "";
  return requestApi<MonsterCardPage>(
    `/api/members/me/monster-cards${query}`,
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 내 도감(010 US1, US4). 카드는 몬스터가 처치될 때마다 늘어나므로 화면을 열 때마다 다시 받는다.
 * 감정을 바꿔 고르는 동안에는 앞의 목록을 그대로 보여 화면이 비지 않게 한다.
 */
export function useMonsterCardsQuery(emotion: EmotionType | null) {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.monsterCards(emotion),
    queryFn: ({ pageParam }) => fetchMonsterCardsPage(pageParam, emotion),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
    refetchOnMount: "always",
    placeholderData: keepPreviousData,
  });
}
