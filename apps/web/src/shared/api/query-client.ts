import { QueryCache, QueryClient } from "@tanstack/react-query";

import { ApiError } from "./api-error";

/**
 * 조회가 401이면 BFF가 refresh까지 해 보고도 실패한 것이다(세션 만료, 무효화).
 * 세 쿠키는 이미 지워졌으므로 지금 화면을 `next`로 들고 로그인 화면으로 간다
 * (US4-AC2, US4-AC3).
 */
function redirectToLogin(): void {
  if (typeof window === "undefined") {
    return;
  }
  const { pathname, search } = window.location;
  if (pathname === "/login") {
    return;
  }
  window.location.assign(
    `/login?next=${encodeURIComponent(`${pathname}${search}`)}`,
  );
}

export function createQueryClient(
  onUnauthorized: () => void = redirectToLogin,
) {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error) => {
        if (error instanceof ApiError && error.status === 401) {
          onUnauthorized();
        }
      },
    }),
    defaultOptions: {
      queries: {
        staleTime: 30 * 1000,
        retry: (failureCount, error) => {
          if (
            error instanceof ApiError &&
            error.status >= 400 &&
            error.status < 500
          ) {
            return false;
          }
          return failureCount < 2;
        },
      },
      mutations: {
        retry: false,
      },
    },
  });
}
