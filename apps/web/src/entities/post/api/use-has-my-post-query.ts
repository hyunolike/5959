"use client";

import { useQuery } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { FeedPage } from "../model/types";

/** 내 글 목록을 한 개만 받아 지우지 않은 내 글이 하나라도 있는지 본다(016 research R1). */
export async function fetchHasMyPost(
  fetchImpl: typeof fetch = fetch,
): Promise<boolean> {
  const page = await requestApi<FeedPage>(
    "/api/members/me/posts?size=1",
    { cache: "no-store" },
    fetchImpl,
  );
  return page.items.length > 0;
}

/**
 * 내 글이 있으면 `true`, 없으면 `false`. 받는 중이거나 실패하면 `undefined`다.
 * `undefined`일 때 부르는 쪽은 첫 고민 카드도 첫 글 표시도 내지 않는다(016 US2-AC9).
 */
export function useHasMyPostQuery(): boolean | undefined {
  const { data } = useQuery({
    queryKey: QUERY_KEYS.hasMyPost,
    queryFn: () => fetchHasMyPost(),
    refetchOnMount: "always",
  });
  return data;
}
