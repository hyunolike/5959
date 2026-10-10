"use client";

import {
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import { mergeRaidFetch, mergeRaidLive } from "../model/merge";
import type { RaidLive, RaidState } from "../model/types";

/** 실시간 연결이 열려 있지 않을 때 값을 다시 받는 주기(006 US2-AC6). */
export const RAID_POLL_INTERVAL_MS = 3_000;

export function fetchRaid(fetchImpl: typeof fetch = fetch): Promise<RaidState> {
  return requestApi<RaidState>("/api/raid", { cache: "no-store" }, fetchImpl);
}

/**
 * 지금의 레이드. 조회 응답은 캐시의 값과 합쳐서 둔다. 조회가 떠난 사이에 실시간으로 받은 더 작은 HP를
 * 늦게 온 응답이 덮지 않는다(US2-AC2).
 *
 * `polling`이면 3초마다 다시 받는다. 실시간 연결이 열려 있으면 켜지 않는다.
 */
export function useRaidQuery({ polling = false }: { polling?: boolean } = {}) {
  const queryClient = useQueryClient();
  return useQuery({
    queryKey: QUERY_KEYS.raid,
    queryFn: async () => {
      const fetched = await fetchRaid();
      return mergeRaidFetch(
        queryClient.getQueryData<RaidState>(QUERY_KEYS.raid),
        fetched,
      );
    },
    refetchInterval: polling ? RAID_POLL_INTERVAL_MS : false,
    staleTime: polling ? 0 : RAID_POLL_INTERVAL_MS,
  });
}

/** 실시간 `raid` 이벤트를 캐시에 반영한다. 이벤트만으로 채울 수 없으면 조회를 다시 한다. */
export function applyRaidLive(queryClient: QueryClient, live: RaidLive): void {
  const cached = queryClient.getQueryData<RaidState>(QUERY_KEYS.raid);
  const { state, refetch } = mergeRaidLive(cached, live);
  if (state !== undefined) {
    queryClient.setQueryData<RaidState>(QUERY_KEYS.raid, state);
  }
  if (refetch) {
    void queryClient.invalidateQueries({ queryKey: QUERY_KEYS.raid });
  }
}
