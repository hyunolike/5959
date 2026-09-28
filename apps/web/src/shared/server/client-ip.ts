import "server-only";

import type { NextRequest } from "next/server";

/**
 * 브라우저의 원래 IP를 찾는다(research R6). `x-forwarded-for`의 첫 값,
 * 없으면 `x-real-ip`, 그것도 없으면 `unknown`을 돌려준다.
 */
export function resolveClientIp(request: NextRequest): string {
  const forwardedFor = request.headers.get("x-forwarded-for");
  if (forwardedFor) {
    const first = forwardedFor.split(",")[0]?.trim();
    if (first) {
      return first;
    }
  }

  const realIp = request.headers.get("x-real-ip");
  if (realIp) {
    return realIp.trim();
  }

  return "unknown";
}
