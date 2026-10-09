"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import {
  mergeRaidAttack,
  type RaidAttackResult,
  type RaidState,
} from "@/entities/raid";
import { ApiError, requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

/**
 * 같은 출처 BFF 프록시를 거친 보스 공격. 쿨다운 안이면 429 `RAID_COOLDOWN`, 보스가 끝났으면 409
 * `RAID_BOSS_ENDED`, 레이드가 쉬는 중이면 503 `RAID_UNAVAILABLE`을 `ApiError`로 던진다.
 */
export function attackRaidBoss(
  bossId: number,
  fetchImpl: typeof fetch = fetch,
): Promise<RaidAttackResult> {
  return requestApi<RaidAttackResult>(
    "/api/raid/attacks",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ bossId }),
    },
    fetchImpl,
  );
}

/**
 * 보스 공격(006 US1). 응답을 레이드 캐시에 합친다. 보스가 끝났다는 응답을 받으면 조회를 다시 해 결과
 * 화면으로 넘어가게 하고, 쉬는 중이라는 응답이면 화면에 그 상태를 적는다.
 */
export function useRaidAttackMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (bossId: number) => attackRaidBoss(bossId),
    onSuccess: (result) => {
      queryClient.setQueryData<RaidState>(QUERY_KEYS.raid, (cached) =>
        mergeRaidAttack(cached, result),
      );
      if (result.defeated) {
        void queryClient.invalidateQueries({ queryKey: QUERY_KEYS.raid });
      }
    },
    onError: (error) => {
      if (!(error instanceof ApiError)) {
        return;
      }
      if (error.code === "RAID_BOSS_ENDED") {
        void queryClient.invalidateQueries({ queryKey: QUERY_KEYS.raid });
      } else if (error.code === "RAID_UNAVAILABLE") {
        queryClient.setQueryData<RaidState>(QUERY_KEYS.raid, (cached) =>
          cached ? { ...cached, available: false } : cached,
        );
      }
    },
  });
}
