"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";

import type { MemberProfile } from "@/entities/member";
import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import { QUERY_KEYS } from "@/shared/config";

import type { OnboardingFormValues } from "../model/schema";

interface OnboardingResponseData {
  member: MemberProfile;
}

/**
 * 같은 출처 BFF 라우트(`/api/auth/onboarding`)만 부른다. 성공하면 서버가
 * `ogu_at`을 새 토큰으로 교체하고 `ogu_ob`를 심어 준다 — 응답 본문에는
 * `member`만 있다(bff-routes.md, FR-012).
 */
export async function completeOnboarding(
  values: OnboardingFormValues,
  fetchImpl: typeof fetch = fetch,
): Promise<MemberProfile> {
  const response = await fetchImpl("/api/auth/onboarding", {
    method: "PUT",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(values),
  });
  const body = (await response.json()) as ApiResponse<OnboardingResponseData>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data.member;
}

export function useOnboardingMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (values: OnboardingFormValues) => completeOnboarding(values),
    onSuccess: (member) => {
      queryClient.setQueryData(QUERY_KEYS.me, member);
    },
  });
}
