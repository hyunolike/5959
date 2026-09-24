// @vitest-environment node
import { describe, expect, it, vi } from "vitest";

import { fetchApiHealth } from "./api-health";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

describe("fetchApiHealth", () => {
  it("US1-AC1 액추에이터가 UP이면 UP을 돌려준다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(jsonResponse({ status: "UP" }));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "UP",
    });
    expect(fetchImpl).toHaveBeenCalledWith(
      "http://api/actuator/health",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("US1-AC2 액추에이터가 DOWN이면 DOWN을 돌려준다", async () => {
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(jsonResponse({ status: "DOWN" }, 503));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "DOWN",
    });
  });

  it("US1-AC2 연결에 실패하면 예외 대신 DOWN을 돌려준다", async () => {
    const fetchImpl = vi.fn().mockRejectedValue(new TypeError("fetch failed"));

    await expect(fetchApiHealth("http://api", fetchImpl)).resolves.toEqual({
      status: "DOWN",
    });
  });

  it("US1-AC2 제한 시간이 지나면 DOWN을 돌려준다", async () => {
    const fetchImpl = vi.fn(
      (_url: string, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("timeout", "TimeoutError")),
          );
        }),
    );

    await expect(
      fetchApiHealth("http://api", fetchImpl as typeof fetch, 10),
    ).resolves.toEqual({ status: "DOWN" });
  });
});
