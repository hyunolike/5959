export type ApiHealthStatus = "UP" | "DOWN";

export interface ApiHealth {
  status: ApiHealthStatus;
}

/**
 * apps/api의 액추에이터 헬스를 조회한다. 어떤 실패도 예외로 던지지 않고
 * DOWN으로 바꿔서, 호출하는 화면이 항상 렌더링되게 한다.
 */
export async function fetchApiHealth(
  apiOrigin: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<ApiHealth> {
  try {
    const origin = apiOrigin.replace(/\/+$/, "");
    const response = await fetchImpl(`${origin}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) {
      return { status: "DOWN" };
    }
    const body = (await response.json()) as { status?: string };
    return { status: body.status === "UP" ? "UP" : "DOWN" };
  } catch {
    return { status: "DOWN" };
  }
}
