import { useQuery } from "@tanstack/react-query";

import type { ApiHealth } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

async function getApiHealth(): Promise<ApiHealth> {
  const response = await fetch("/api/health", { cache: "no-store" });
  if (!response.ok) {
    return { status: "DOWN" };
  }
  return (await response.json()) as ApiHealth;
}

export function useApiHealthQuery() {
  return useQuery({
    queryKey: QUERY_KEYS.apiHealth,
    queryFn: getApiHealth,
    refetchInterval: 30_000,
  });
}
