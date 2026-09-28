import "server-only";

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { env } from "@/shared/config";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * 상태를 바꾸는 요청(POST, PUT, PATCH, DELETE)의 `Origin`이 서비스 출처와
 * 다르거나 없으면 403 FORBIDDEN_ORIGIN을 돌려준다. GET, HEAD는 항상 통과한다.
 * 통과하면 null을 돌려준다.
 */
export function guardOrigin(request: NextRequest): NextResponse | null {
  if (!UNSAFE_METHODS.has(request.method)) {
    return null;
  }

  const origin = request.headers.get("origin");
  if (origin === env.APP_ORIGIN) {
    return null;
  }

  return NextResponse.json(
    {
      success: false,
      data: null,
      error: {
        code: "FORBIDDEN_ORIGIN",
        message: "허용되지 않은 요청 출처입니다.",
      },
    },
    { status: 403 },
  );
}
