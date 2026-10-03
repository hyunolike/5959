"use client";

import { useInfiniteQuery } from "@tanstack/react-query";

import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { FeedFilter, FeedPage } from "../model/types";

/**
 * 피드 한 쪽의 BFF 경로. 기본값(최신순)과 빈 필터는 빼고, 직군과 경력은 같은 이름을
 * 여러 번 쓴다(계약 `getFeed`의 `style: form, explode: true`).
 */
export function feedRequestPath(
  filter: FeedFilter,
  cursor: string | null,
): string {
  const params = new URLSearchParams();
  if (filter.order !== "LATEST") {
    params.set("order", filter.order);
  }
  filter.jobRoles.forEach((jobRole) => params.append("jobRole", jobRole));
  filter.careerYears.forEach((careerYear) =>
    params.append("careerYear", careerYear),
  );
  if (cursor) {
    params.set("cursor", cursor);
  }
  const query = params.toString();
  return query ? `/api/feed?${query}` : "/api/feed";
}

/** 같은 출처 BFF 프록시(`/api/[...path]`)를 거쳐 피드 한 쪽을 가져온다. */
export async function fetchFeedPage(
  filter: FeedFilter,
  cursor: string | null,
  fetchImpl: typeof fetch = fetch,
): Promise<FeedPage> {
  const response = await fetchImpl(feedRequestPath(filter, cursor), {
    cache: "no-store",
  });
  const body = (await response.json()) as ApiResponse<FeedPage>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

/** 피드(US2). 키셋 커서로 20개씩 이어 붙인다(US2-AC2, research R7). */
export function useFeedQuery(filter: FeedFilter) {
  return useInfiniteQuery({
    queryKey: QUERY_KEYS.feed(filter),
    queryFn: ({ pageParam }) => fetchFeedPage(filter, pageParam),
    initialPageParam: null as string | null,
    getNextPageParam: (lastPage) => lastPage.nextCursor ?? undefined,
  });
}
