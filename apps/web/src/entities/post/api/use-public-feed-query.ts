"use client";

import { useQuery } from "@tanstack/react-query";

import type { ApiResponse } from "@/shared/api";
import { ANONYMOUS_QUERY_META, ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { PublicFeed } from "../model/types";

/** 로그인 없이 받는 최근 글. 같은 출처 BFF 프록시가 쿠키 없이도 그대로 넘긴다. */
export async function fetchPublicFeed(
  fetchImpl: typeof fetch = fetch,
): Promise<PublicFeed> {
  const response = await fetchImpl("/api/public/feed", { cache: "no-store" });
  const body = (await response.json()) as ApiResponse<PublicFeed>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

/**
 * 비회원 둘러보기 피드(011). 최근 20개뿐이고 다음 쪽이 없다(US1-AC5).
 * 실패가 401이어도 로그인 화면으로 보내지 않는다(US1-AC7).
 */
export function usePublicFeedQuery() {
  return useQuery({
    queryKey: QUERY_KEYS.publicFeed,
    queryFn: () => fetchPublicFeed(),
    meta: ANONYMOUS_QUERY_META,
  });
}
