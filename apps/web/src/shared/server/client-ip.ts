import "server-only";

import { isIP } from "node:net";

import type { NextRequest } from "next/server";

function firstValidIp(...candidates: (string | undefined)[]): string | null {
  for (const candidate of candidates) {
    if (candidate && isIP(candidate) !== 0) {
      return candidate;
    }
  }
  return null;
}

/**
 * 브라우저의 원래 IP를 찾는다(research R6). `x-forwarded-for`의 첫 값,
 * 없으면 `x-real-ip`, 그것도 없으면 `unknown`을 돌려준다. 각 값은
 * `net.isIP`로 실제 IPv4/IPv6 형식인지 검증하고, 아니면 다음 후보로
 * 넘어간다(둘 다 아니면 `unknown`). 헤더는 클라이언트가 보낸 값이라 그대로
 * 신뢰하지 않는다.
 */
export function resolveClientIp(request: NextRequest): string {
  const forwardedFor = request.headers
    .get("x-forwarded-for")
    ?.split(",")[0]
    ?.trim();
  const realIp = request.headers.get("x-real-ip")?.trim();

  return firstValidIp(forwardedFor, realIp) ?? "unknown";
}
