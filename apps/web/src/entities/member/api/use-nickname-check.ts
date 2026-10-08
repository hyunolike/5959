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

/**
 * 입력 뒤 400ms 디바운스 뒤에만 확인한다(T038). 빈 값이면 확인하지 않는다.
 *
 * `isSettled`는 지금 돌려주는 `data`가 *지금* 입력값(트림한 값)에 대한
 * 결과인지 나타낸다. 디바운스가 아직 끝나지 않았으면(사용자가 막 고쳐
 * 썼으면) `data`는 이전 값의 결과이므로 false다 — 호출하는 쪽은 이 값이
 * false일 때 오래된 결과로 제출을 막으면 안 된다.
 */
export function useNicknameCheck(nickname: string) {
  const trimmedNickname = nickname.trim();
  const debouncedNickname = useDebouncedValue(trimmedNickname, DEBOUNCE_MS);

  const query = useQuery({
    queryKey: ["nickname-availability", debouncedNickname],
    queryFn: () => fetchNicknameAvailability(debouncedNickname),
    enabled: debouncedNickname.length > 0,
    retry: false,
    staleTime: 0,
  });

  return { ...query, isSettled: debouncedNickname === trimmedNickname };
}
