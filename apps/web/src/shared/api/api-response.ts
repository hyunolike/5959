import type { components } from "./generated";

export type ApiErrorResponse = components["schemas"]["ErrorResponse"];
export type ApiErrorEnvelope = components["schemas"]["ErrorEnvelope"];

/**
 * apps/api가 모든 응답에 쓰는 봉투(openapi.yaml). 성공이면 `data`에 값이,
 * 실패면 `error`에 코드와 메시지가 담긴다. generated.ts는 스키마별로
 * `AuthResultEnvelope`, `MemberProfileEnvelope`처럼 나눠 생성하므로, 이 타입이
 * BFF 쪽에서 쓰는 공용 이름이다.
 */
export type ApiResponse<T> =
  { success: true; data: T; error: null } | ApiErrorEnvelope;
