import "server-only";

import type { RouteGuardAction } from "./route-guard";

/** 글 상세. 글 고치기(`/post/1/edit`)는 들지 않는다. */
const POST_DETAIL = /^\/post\/\d+$/;

/**
 * 점검 모드(`OGU_MAINTENANCE_MODE`)에서 페이지 요청을 어디로 보낼지 정한다.
 * API가 꺼져 있어 첫 화면(서비스 소개, API를 부르지 않는다) 말고는 열어도 오류만 나므로,
 * 모든 화면을 첫 화면으로 보내고 첫 화면이 이사 안내를 그린다. `public`의 파일은 그대로 내준다.
 */
export function resolveMaintenanceAction(pathname: string): RouteGuardAction {
  if (pathname === "/") return null;

  const lastSegment = pathname.slice(pathname.lastIndexOf("/") + 1);
  if (lastSegment.includes(".")) return null;

  return {
    type: "redirect",
    to: POST_DETAIL.test(pathname) ? "/?from=post" : "/",
  };
}
