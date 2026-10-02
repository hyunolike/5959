"use client";

import { useMutation } from "@tanstack/react-query";

import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import type { MemberProfile } from "@/entities/member";

import type { SignupFormValues } from "../model/schema";

interface SignupResponseData {
  member: MemberProfile;
}

/**
 * 같은 출처 BFF 라우트(`/api/auth/signup`)만 부른다. 성공하면 서버가
 * `ogu_at`, `ogu_rt` 쿠키를 심어 준다 — 응답 본문에는 `member`만 있다
 * (bff-routes.md, FR-012).
 */
export async function signup(
  values: SignupFormValues,
  fetchImpl: typeof fetch = fetch,
): Promise<MemberProfile> {
  const response = await fetchImpl("/api/auth/signup", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(values),
  });
  const body = (await response.json()) as ApiResponse<SignupResponseData>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data.member;
}

export function useSignupMutation() {
  return useMutation({
    mutationFn: (values: SignupFormValues) => signup(values),
  });
}
