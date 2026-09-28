import "server-only";

import type { ApiErrorResponse, ApiResponse } from "@/shared/api";
import { env } from "@/shared/config";

export interface ApiClientOptions extends Omit<RequestInit, "headers"> {
  headers?: HeadersInit;
}

export interface ApiClientResult<T> {
  status: number;
  body: ApiResponse<T>;
}

const UPSTREAM_UNAVAILABLE_ERROR: ApiErrorResponse = {
  code: "API_UNAVAILABLE",
  message: "서버에 연결할 수 없습니다.",
};

function buildUrl(path: string): string {
  const origin = env.API_ORIGIN.replace(/\/+$/, "");
  const normalizedPath = path.startsWith("/") ? path : `/${path}`;
  return `${origin}${normalizedPath}`;
}

/**
 * apps/api를 호출하는 유일한 통로. `X-Ogu-Bff-Key`, `X-Ogu-Client-Ip`를 붙이고
 * 응답을 `ApiResponse` 봉투로 파싱한다. 네트워크 실패는 예외를 던지지 않고
 * 502와 오류 봉투로 바꿔서, 호출하는 라우트 핸들러가 항상 같은 모양을 다룬다.
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

  try {
    const response = await fetch(buildUrl(path), {
      ...rest,
      headers: requestHeaders,
    });
    const body = (await response.json()) as ApiResponse<T>;
    return { status: response.status, body };
  } catch {
    return {
      status: 502,
      body: { success: false, data: null, error: UPSTREAM_UNAVAILABLE_ERROR },
    };
  }
}
