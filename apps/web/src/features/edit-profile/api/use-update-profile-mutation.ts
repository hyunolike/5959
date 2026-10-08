"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { MemberProfile } from "@/entities/member";
import { requestApi } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { ProfileChanges } from "../model/schema";

/**
 * 같은 출처 BFF 프록시를 거친 프로필 수정. 바뀐 항목만 보낸다. 닉네임이 겹치면 409 `NICKNAME_TAKEN`,
 * 형식이 틀리면 400 `INVALID_REQUEST`를 `ApiError`로 던진다.
 */
export function updateProfile(
  changes: ProfileChanges,
  fetchImpl: typeof fetch = fetch,
): Promise<MemberProfile> {
  return requestApi<MemberProfile>(
    "/api/members/me",
    {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(changes),
    },
    fetchImpl,
  );
}

/**
 * 프로필 수정(004 US5). 성공하면 내 정보를 응답으로 바꾸고, 닉네임이 보이는 화면(피드, 글 상세와 댓글,
 * 알림 목록, 마이페이지 목록)을 낡은 것으로 표시해 바뀐 닉네임으로 다시 받게 한다(US5-AC4).
 */
export function useUpdateProfileMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (changes: ProfileChanges) => updateProfile(changes),
    onSuccess: (member) => {
      queryClient.setQueryData(QUERY_KEYS.me, member);
      return Promise.all(
        [
          QUERY_KEYS.allFeeds,
          QUERY_KEYS.allPosts,
          QUERY_KEYS.notifications,
          QUERY_KEYS.myActivity,
        ].map((queryKey) => queryClient.invalidateQueries({ queryKey })),
      );
    },
  });
}
