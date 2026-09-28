import "server-only";

import type { ApiErrorResponse, ApiResponse } from "@/shared/api";
import { env } from "@/shared/config";

export interface ApiClientOptions extends Omit<RequestInit, "headers"> {
  headers?: HeadersInit;
}

export interface ApiClientResult<T> {
  status: number;
  /** 204이거나 본문이 비어 있으면 null. */
  body: ApiResponse<T> | null;
}

const UPSTREAM_TIMEOUT_MS = 15_000;

const UPSTREAM_UNAVAILABLE_ERROR: ApiErrorResponse = {
  code: "API_UNAVAILABLE",
  message: "서버에 연결할 수 없습니다.",
};

function isRedirectStatus(status: number): boolean {
  return status >= 300 && status < 400;
}

/** apps/api의 ErrorCode.kt와 같은 메시지를 쓴다. */
function nonJsonBodyError(status: number): ApiErrorResponse {
  if (status >= 400 && status < 500) {
    return { code: "INVALID_REQUEST", message: "잘못된 요청입니다." };
  }
  return { code: "INTERNAL_ERROR", message: "서버 오류가 발생했습니다." };
}

function buildUrl(path: string): string {
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  const url = new URL(normalizedPath, env.API_ORIGIN);
  if (!url.pathname.startsWith("/api/v1/")) {
    // route.ts가 먼저 경로를 검증하므로 정상 흐름에서는 일어나지 않는다.
    // 그래도 여기서 한 번 더 막아, 잘못된 호출이 실수로 다른 경로를
    // 부르지 않게 한다.
    throw new Error(
      `api-client: 예상하지 못한 업스트림 경로를 거부한다: ${url.pathname}`,
    );
  }
  return url.href;
}

/**
 * apps/api를 호출하는 유일한 통로. `X-Ogu-Bff-Key`, `X-Ogu-Client-Ip`를 붙이고
 * 15초 안에 응답이 없으면 타임아웃으로 본다. 응답을 `ApiResponse` 봉투로
 * 파싱한다.
 *
 * - 네트워크 실패는 502, 타임아웃은 504로 바꾸고 콘솔에 원인을 남긴다(요청
 *   헤더나 토큰 같은 시크릿은 남기지 않는다).
 * - `redirect: "manual"`을 붙여 업스트림 리다이렉트를 절대 따라가지 않는다.
 *   따라가면 `X-Ogu-Bff-Key`가 실려 있는 요청이 리다이렉트 대상(신뢰할 수 없을
 *   수도 있는 주소)으로 그대로 다시 나간다. 3xx 응답은 502 API_UNAVAILABLE
 *   오류 봉투로 바꾼다.
 * - 204이거나 본문이 비어 있으면 상태만 그대로 돌려주고 본문은 null이다.
 * - JSON으로 파싱할 수 없는 본문은 원래 상태 코드를 유지한 채 오류 봉투로
 *   감싼다(5xx는 INTERNAL_ERROR, 그 외 4xx는 INVALID_REQUEST).
 */
export async function callApi<T>(
  path: string,
  clientIp: string,
  options: ApiClientOptions = {},
): Promise<ApiClientResult<T>> {
  const { headers, ...rest } = options;
  const requestHeaders = new Headers(headers);
  requestHeaders.set("X-Ogu-Bff-Key", env.BFF_API_KEY);
  requestHeaders.set("X-Ogu-Client-Ip", clientIp);

  let response: Response;
  try {
    response = await fetch(buildUrl(path), {
      ...rest,
      headers: requestHeaders,
      redirect: "manual",
      signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
    });
  } catch (error) {
    const isTimeout = error instanceof Error && error.name === "TimeoutError";
    // 시크릿(헤더, 토큰)을 담지 않는 진단 로그.
    console.error("[api-client] 업스트림 호출에 실패했다", error);
    return {
      status: isTimeout ? 504 : 502,
      body: { success: false, data: null, error: UPSTREAM_UNAVAILABLE_ERROR },
    };
  }

  if (isRedirectStatus(response.status)) {
    console.error(
      `[api-client] 업스트림이 리다이렉트를 돌려줘 따라가지 않고 오류로 바꾼다: ${response.status}`,
    );
    return {
      status: 502,
      body: { success: false, data: null, error: UPSTREAM_UNAVAILABLE_ERROR },
    };
  }

  if (response.status === 204) {
    return { status: response.status, body: null };
  }

  const text = await response.text();
  if (text.length === 0) {
    return { status: response.status, body: null };
  }

  try {
    return {
      status: response.status,
      body: JSON.parse(text) as ApiResponse<T>,
    };
  } catch {
    return {
      status: response.status,
      body: {
        success: false,
        data: null,
        error: nonJsonBodyError(response.status),
      },
    };
  }
}
