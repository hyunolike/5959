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

let redirectPaused = false;

/**
 * 회원이 스스로 세션을 끝내는 동안(013 탈퇴)에는 조회의 401을 세션 만료로 보지 않는다.
 * 탈퇴가 받아들여지면 화면에 떠 있던 조회들이 401을 받는다. 그것을 세션 만료로 다루면
 * `/login?next=`로 먼저 튀어, 탈퇴가 끝났다는 안내 없이 로그인 화면에 닿는다.
 * 돌려준 함수를 부르면 다시 세션 만료로 본다. 탈퇴에 실패했을 때 부른다.
 */
export function pauseUnauthorizedRedirect(): () => void {
  redirectPaused = true;
  return () => {
    redirectPaused = false;
  };
}

/**
 * 로그인 없이 부르는 조회에 붙이는 표시(011). 이런 조회의 401은 세션 만료가 아니다.
 * 웹이 API보다 먼저 배포되면 옛 API는 모르는 경로에 401을 준다. 로그인하지 않은 방문자를
 * 로그인 화면으로 보내지 않고 화면이 실패 안내를 보이게 둔다.
 */
export const ANONYMOUS_QUERY_META = { anonymous: true } as const;

export function createQueryClient(
  onUnauthorized: () => void = redirectToLogin,
) {
  return new QueryClient({
    queryCache: new QueryCache({
      onError: (error, query) => {
        if (query.meta?.anonymous === true || redirectPaused) {
          return;
        }
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
