"use client";

import { useMutation } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";

/**
 * 같은 출처 BFF 라우트만 부른다. 실패는 상태와 오류 코드를 담은 `ApiError`로 던진다.
 * 요청은 가입 여부와 상관없이 204다(015 FR-002).
 */
export function requestPasswordReset(
  email: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  return requestApi<void>(
    "/api/auth/password-reset",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ email }),
    },
    fetchImpl,
  );
}

export function confirmPasswordReset(
  token: string,
  password: string,
  fetchImpl: typeof fetch = fetch,
): Promise<void> {
  return requestApi<void>(
    "/api/auth/password-reset/confirm",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ token, password }),
    },
    fetchImpl,
  );
}

export function useRequestPasswordResetMutation() {
  return useMutation({
    mutationFn: (email: string) => requestPasswordReset(email),
  });
}

export function useConfirmPasswordResetMutation() {
  return useMutation({
    mutationFn: ({ token, password }: { token: string; password: string }) =>
      confirmPasswordReset(token, password),
  });
}
