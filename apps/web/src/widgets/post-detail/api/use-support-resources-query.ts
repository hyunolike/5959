"use client";

import { useQuery } from "@tanstack/react-query";

import { requestApi, type components } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

export type SupportResource = components["schemas"]["SupportResource"];

/** 같은 출처 BFF 프록시를 거쳐 도움 리소스(상담 전화)를 가져온다. */
export function fetchSupportResources(
  fetchImpl: typeof fetch = fetch,
): Promise<SupportResource[]> {
  return requestApi<SupportResource[]>(
    "/api/safety/support-resources",
    { cache: "no-store" },
    fetchImpl,
  );
}

/**
 * 도움 리소스(005 US1-AC1). 안내가 보일 때만 받는다. 번호는 바뀌는 일이 드물어 한 시간 동안 다시 받지 않는다.
 */
export function useSupportResourcesQuery({ enabled = true } = {}) {
  return useQuery({
    queryKey: QUERY_KEYS.supportResources,
    queryFn: () => fetchSupportResources(),
    enabled,
    staleTime: 60 * 60 * 1000,
  });
}
