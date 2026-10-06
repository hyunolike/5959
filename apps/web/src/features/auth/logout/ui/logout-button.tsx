"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";

import { Button } from "@/shared/ui";

import { useLogoutMutation } from "../api/use-logout-mutation";

interface LogoutButtonProps {
  /**
   * 로그아웃에 성공했을 때(쿠키가 지워진 뒤) 한 번 부른다. 화면이 이 세션에 묶인 것(실시간 알림
   * 연결 등)을 닫는 데 쓴다. features끼리는 서로 가져다 쓰지 않으므로 화면이 이어 준다.
   */
  onLoggedOut?: () => void;
}

/**
 * 로그아웃 버튼. BFF가 API 결과와 관계없이 쿠키를 지우므로(US2-AC5), 여기서도
 * 요청이 실패해도 쿼리 캐시를 비우고 `/login`으로 이동한다 — 뒤로 가기로
 * `/home`에 돌아가도 이전 사용자 데이터가 캐시에 남아 있으면 안 된다.
 *
 * 이동한 뒤 `router.refresh()`로 루트 레이아웃을 새로 받는다. 레이아웃은 쿠키를 보고 알림 종을
 * 그리는데, 클라이언트 이동만으로는 다시 그려지지 않아 로그아웃 뒤에도 종이 남는다(US1-AC8).
 */
export function LogoutButton({ onLoggedOut }: LogoutButtonProps = {}) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const logoutMutation = useLogoutMutation();

  const handleLogout = async () => {
    try {
      await logoutMutation.mutateAsync();
      onLoggedOut?.();
    } catch {
      // 요청이 실패해도 아래에서 로그인 화면으로 보낸다. 쿠키가 남아 있으면 라우트 가드가 되돌린다.
    } finally {
      queryClient.clear();
      router.push("/login");
      router.refresh();
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
