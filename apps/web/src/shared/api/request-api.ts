import { ApiError } from "./api-error";
import type { ApiResponse } from "./api-response";

/**
 * 같은 출처 BFF(`/api/...`)를 불러 봉투를 벗긴 `data`를 돌려준다. 실패 봉투면
 * 상태 코드와 오류 코드를 담은 `ApiError`를 던진다.
 */
export async function requestApi<T>(
  path: string,
  init?: RequestInit,
  fetchImpl: typeof fetch = fetch,
): Promise<T> {
  const response = await fetchImpl(path, init);
  const body = (await response.json()) as ApiResponse<T>;

  if (!body.success) {
    throw new ApiError(response.status, {
      message: body.error.message,
      code: body.error.code,
    });
  }

  return body.data;
}
