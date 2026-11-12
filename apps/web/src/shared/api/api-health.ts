export type ApiHealthStatus = "UP" | "DOWN";

export interface ApiHealth {
  status: ApiHealthStatus;
}

async function fetchActuator(
  url: string,
  fetchImpl: typeof fetch,
  timeoutMs: number,
): Promise<ApiHealth> {
  try {
    const response = await fetchImpl(url, {
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

const trimOrigin = (apiOrigin: string) => apiOrigin.replace(/\/+$/, "");

/**
 * apps/api의 액추에이터 헬스를 조회한다. 어떤 실패도 예외로 던지지 않고
 * DOWN으로 바꿔서, 호출하는 화면이 항상 렌더링되게 한다.
 * 전체 헬스는 DB 연결도 확인하므로 잠든 DB를 깨운다. 서버가 떠 있는지만 볼 때는 `fetchApiLiveness`를 쓴다.
 */
export async function fetchApiHealth(
  apiOrigin: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<ApiHealth> {
  return fetchActuator(
    `${trimOrigin(apiOrigin)}/actuator/health`,
    fetchImpl,
    timeoutMs,
  );
}

/**
 * apps/api가 떠 있는지만 묻는다(`/actuator/health/liveness`). DB를 보지 않아 잠든 DB를 깨우지 않는다(018 US2-AC3).
 * 잠든 API에는 이 요청이 깨우는 신호가 된다(018 US2-AC7).
 */
export async function fetchApiLiveness(
  apiOrigin: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<ApiHealth> {
  return fetchActuator(
    `${trimOrigin(apiOrigin)}/actuator/health/liveness`,
    fetchImpl,
    timeoutMs,
  );
}
