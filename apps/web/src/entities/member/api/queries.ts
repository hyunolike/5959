import { useQuery } from "@tanstack/react-query";

import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { MemberProfile } from "../model/types";

/**
 * 같은 출처 BFF 프록시(`/api/[...path]`)를 거쳐 내 프로필을 가져온다.
 * 브라우저는 API_ORIGIN을 직접 부르지 않는다.
 */
export async function fetchMe(
  fetchImpl: typeof fetch = fetch,
): Promise<MemberProfile> {
  const response = await fetchImpl("/api/members/me", { cache: "no-store" });
  const body = (await response.json()) as ApiResponse<MemberProfile>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

export function useMeQuery() {
  return useQuery({
    queryKey: QUERY_KEYS.me,
    queryFn: () => fetchMe(),
  });
}
