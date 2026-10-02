import "server-only";

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

import { env } from "@/shared/config";

const UNSAFE_METHODS = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * `URL#origin`으로 정규화한다(스킴+호스트+포트만 남고 소문자로 맞춰진다).
 * 파싱할 수 없으면(예: 불투명 출처 `Origin: null`) null을 돌려준다.
 */
function toOrigin(value: string): string | null {
  try {
    return new URL(value).origin;
  } catch {
    return null;
  }
}

/**
 * 상태를 바꾸는 요청(POST, PUT, PATCH, DELETE)의 `Origin`이 서비스 출처와
 * 다르거나 없으면 403 FORBIDDEN_ORIGIN을 돌려준다. GET, HEAD는 항상 통과한다.
 * 통과하면 null을 돌려준다. `URL#origin`으로 정규화해서 비교하므로
 * `APP_ORIGIN` 끝의 슬래시나 대소문자 차이에 흔들리지 않는다.
 */
export function guardOrigin(request: NextRequest): NextResponse | null {
  if (!UNSAFE_METHODS.has(request.method)) {
    return null;
  }

  const origin = request.headers.get("origin");
  const requestOrigin = origin === null ? null : toOrigin(origin);
  const appOrigin = toOrigin(env.APP_ORIGIN);

  if (requestOrigin !== null && requestOrigin === appOrigin) {
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
