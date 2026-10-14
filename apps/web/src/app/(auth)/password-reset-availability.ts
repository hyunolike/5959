import "server-only";

import type { components } from "@/shared/api";
import { callApi } from "@/shared/server";

type Availability = components["schemas"]["PasswordResetAvailability"];

/** 켜고 끄는 일이 드물어 1분 동안 다시 묻지 않는다. 켠 뒤 1분 안에 들어가는 길이 보인다(015 US4-AC3). */
const REVALIDATE_SECONDS = 60;

/** 로그인 화면이 이 답을 기다리며 그려진다. API가 멈춰 있어도 화면은 금방 뜨고 들어가는 길만 빠진다. */
const TIMEOUT_MS = 2_000;

/**
 * 비밀번호 재설정을 쓸 수 있는지(메일 발송이 켜져 있는지) 서버에서 묻는다(015 research R6). 웹의 환경 변수가 아니라
 * API의 설정을 따르므로 켤 때 웹을 다시 내지 않는다. API에 닿지 못하면 꺼진 것으로 본다. 로그인 화면은 그대로 뜬다.
 */
export async function fetchPasswordResetAvailable(): Promise<boolean> {
  const { status, body } = await callApi<Availability>(
    "/api/v1/auth/password-reset",
    "unknown",
    {
      method: "GET",
      next: { revalidate: REVALIDATE_SECONDS },
      timeoutMs: TIMEOUT_MS,
    },
  );
  return status === 200 && body?.success === true && body.data.available;
}
