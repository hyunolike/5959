"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";

import { Button } from "@/shared/ui";

import { useLogoutMutation } from "../api/use-logout-mutation";

/**
 * 로그아웃 버튼. BFF가 API 결과와 관계없이 쿠키를 지우므로(US2-AC5), 여기서도
 * 요청이 실패해도 쿼리 캐시를 비우고 `/login`으로 이동한다 — 뒤로 가기로
 * `/home`에 돌아가도 이전 사용자 데이터가 캐시에 남아 있으면 안 된다.
 */
export function LogoutButton() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const logoutMutation = useLogoutMutation();

  const handleLogout = async () => {
    try {
      await logoutMutation.mutateAsync();
    } finally {
      queryClient.clear();
      router.push("/login");
    }
  };

  return (
    <Button
      type="button"
      variant="outline"
      onClick={handleLogout}
      disabled={logoutMutation.isPending}
    >
      로그아웃
    </Button>
  );
}
