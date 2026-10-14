"use client";

import { useMutation } from "@tanstack/react-query";

import { requestApi } from "@/shared/api";

/**
 * 같은 출처 BFF 라우트(`/api/auth/withdraw`)만 부른다. BFF는 탈퇴에 성공했을 때만(204) 쿠키를
 * 지운다. 실패하면 오류 코드를 담은 `ApiError`를 던지고 로그인 상태는 그대로다(013 US1-AC8).
 * `confirmed`는 화면의 확인 칸과 같은 것을 서버도 보게 하는 값이다(FR-003).
 */
export function withdraw(fetchImpl: typeof fetch = fetch): Promise<void> {
  return requestApi<void>(
    "/api/auth/withdraw",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ confirmed: true }),
    },
    fetchImpl,
  );
}

export function useWithdrawMutation() {
  return useMutation({
    mutationFn: () => withdraw(),
  });
}
