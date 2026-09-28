"use client";

import { useMutation } from "@tanstack/react-query";

import type { ApiResponse } from "@/shared/api";
import { ApiError } from "@/shared/api";
import type { MemberProfile } from "@/entities/member";

import type { LoginFormValues } from "../model/schema";

interface LoginResponseData {
  member: MemberProfile;
}

/**
 * 같은 출처 BFF 라우트(`/api/auth/login`)만 부른다. 성공하면 서버가 `ogu_at`,
 * `ogu_rt`(, 온보딩했으면 `ogu_ob`) 쿠키를 심어 준다 — 응답 본문에는 `member`만
 * 있다(bff-routes.md, FR-012).
 */
export async function login(
  values: LoginFormValues,
  fetchImpl: typeof fetch = fetch,
): Promise<MemberProfile> {
  const response = await fetchImpl("/api/auth/login", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(values),
  });
  const body = (await response.json()) as ApiResponse<LoginResponseData>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
      retryAfterSeconds: body.error.retryAfterSeconds,
    });
  }

  return body.data.member;
}

export function useLoginMutation() {
  return useMutation({
    mutationFn: (values: LoginFormValues) => login(values),
  });
}
