"use client";

import { useMutation } from "@tanstack/react-query";

/**
 * 같은 출처 BFF 라우트(`/api/auth/logout`)만 부른다. BFF는 API 결과와
 * 관계없이 세 쿠키를 모두 지우고 204를 돌려준다(bff-routes.md, US2-AC5).
 */
export async function logout(fetchImpl: typeof fetch = fetch): Promise<void> {
  await fetchImpl("/api/auth/logout", { method: "POST" });
}

export function useLogoutMutation() {
  return useMutation({
    mutationFn: () => logout(),
  });
}
