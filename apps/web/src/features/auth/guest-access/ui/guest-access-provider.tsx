"use client";

import { usePathname, useRouter } from "next/navigation";
import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from "react";

import { sanitizeNextPath, withNextPath } from "@/shared/lib";
import { ConfirmDialog } from "@/shared/ui";

const GuestAccessContext = createContext<{
  authenticated: boolean;
  requestLogin: (destination: string) => void;
} | null>(null);

/** UI 안내만 담당한다. 실제 읽기/쓰기 권한은 기존 BFF와 API가 계속 검사한다. */
export function GuestAccessProvider({
  authenticated,
  children,
}: {
  authenticated: boolean;
  children: ReactNode;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [request, setRequest] = useState<{
    origin: string;
    next: string;
    pending: boolean;
  } | null>(null);

  // 경로가 바뀐 안내는 재방문/뒤로 가기 때 다시 열리지 않는다.
  if (request && (request.origin !== pathname || authenticated))
    setRequest(null);

  const requestLogin = useCallback(
    (destination: string) => {
      if (authenticated) return;
      setRequest(
        (current) =>
          current ?? {
            origin: pathname,
            next: sanitizeNextPath(destination),
            pending: false,
          },
      );
    },
    [authenticated, pathname],
  );

  return (
    <GuestAccessContext.Provider value={{ authenticated, requestLogin }}>
      {children}
      <ConfirmDialog
        open={request !== null && request.origin === pathname && !authenticated}
        title="로그인이 필요해요"
        description="글 전체를 읽거나 다른 메뉴를 이용하려면 로그인해 주세요. 로그인 후 선택한 화면으로 갈게요."
        confirmLabel="로그인하기"
        cancelLabel="계속 둘러보기"
        role="dialog"
        presentation="sheet"
        confirmVariant="primary"
        dismissOnBack
        pending={request?.pending}
        onCancel={() => setRequest(null)}
        onConfirm={() => {
          if (!request || request.pending) return;
          setRequest({ ...request, pending: true });
          // 안내창 history entry를 로그인으로 바꿔 뒤로 가기는 홈으로 돌아온다.
          router.replace(withNextPath("/login", request.next));
        }}
      />
    </GuestAccessContext.Provider>
  );
}

export function useGuestAccess() {
  const context = useContext(GuestAccessContext);
  if (!context) throw new Error("GuestAccessProvider is required");
  return context;
}
