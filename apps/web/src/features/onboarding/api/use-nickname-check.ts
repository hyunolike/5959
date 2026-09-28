"use client";

import { useQuery } from "@tanstack/react-query";

import type { ApiResponse, components } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { useDebouncedValue } from "@/shared/lib";

type NicknameAvailability = components["schemas"]["NicknameAvailability"];

const DEBOUNCE_MS = 400;

/**
 * 같은 출처 BFF 프록시(`/api/members/nickname-availability`)를 거쳐 닉네임
 * 중복 여부를 확인한다(US1-AC5, US1-AC6).
 */
export async function fetchNicknameAvailability(
  nickname: string,
  fetchImpl: typeof fetch = fetch,
): Promise<NicknameAvailability> {
  const response = await fetchImpl(
    `/api/members/nickname-availability?nickname=${encodeURIComponent(nickname)}`,
    { cache: "no-store" },
  );
  const body = (await response.json()) as ApiResponse<NicknameAvailability>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}

/** 입력 뒤 400ms 디바운스 뒤에만 확인한다(T038). 빈 값이면 확인하지 않는다. */
export function useNicknameCheck(nickname: string) {
  const debouncedNickname = useDebouncedValue(nickname.trim(), DEBOUNCE_MS);

  return useQuery({
    queryKey: ["nickname-availability", debouncedNickname],
    queryFn: () => fetchNicknameAvailability(debouncedNickname),
    enabled: debouncedNickname.length > 0,
    retry: false,
    staleTime: 0,
  });
}
