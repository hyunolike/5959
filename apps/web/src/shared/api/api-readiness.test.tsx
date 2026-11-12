import { act, render, screen, waitFor } from "@testing-library/react";
import { useEffect } from "react";
import { describe, expect, it, vi } from "vitest";

import {
  ApiReadinessProvider,
  useApiReadiness,
  type ApiReadiness,
} from "./api-readiness";

const health = (status: "UP" | "DOWN") =>
  Promise.resolve(
    new Response(JSON.stringify({ status }), {
      status: 200,
      headers: { "content-type": "application/json" },
    }),
  );

/** 순서대로 답하고, 다 쓰면 마지막 답을 되풀이한다. */
function healthSequence(...answers: Array<"UP" | "DOWN">) {
  let call = 0;
  return vi.fn(() => {
    const answer = answers[Math.min(call, answers.length - 1)];
    call += 1;
    return health(answer);
  }) as unknown as typeof fetch & ReturnType<typeof vi.fn>;
}

let captured: ApiReadiness | null = null;
function Probe() {
  const readiness = useApiReadiness();
  useEffect(() => {
    captured = readiness;
  }, [readiness]);
  return <p>status:{readiness.status}</p>;
}

function renderProvider(
  fetchImpl: typeof fetch,
  options: { giveUpAfterMs?: number; staleAfterMs?: number } = {},
) {
  render(
    <ApiReadinessProvider
      fetchImpl={fetchImpl}
      pollIntervalMs={10}
      {...options}
    >
      <Probe />
    </ApiReadinessProvider>,
  );
}

describe("ApiReadinessProvider", () => {
  it("US2-AC7 깨어 있으면 깨우는 중을 거치지 않고 바로 ready가 된다", async () => {
    const fetchImpl = healthSequence("UP");
    renderProvider(fetchImpl);

    expect(screen.getByText("status:checking")).toBeInTheDocument();
    await screen.findByText("status:ready");
    expect(fetchImpl).toHaveBeenCalledWith("/api/health", {
      cache: "no-store",
    });
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });

  it("US2-AC7 잠들어 있으면 waking이 되고, 깨어날 때까지 다시 묻는다", async () => {
    const fetchImpl = healthSequence("DOWN", "DOWN", "UP");
    renderProvider(fetchImpl);

    await screen.findByText("status:waking");
    await screen.findByText("status:ready");
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("US2-AC7 헬스 요청이 실패해도 예외 없이 깨우는 중으로 본다", async () => {
    let call = 0;
    const fetchImpl = vi.fn(() =>
      call++ === 0
        ? Promise.reject(new TypeError("fetch failed"))
        : health("UP"),
    ) as unknown as typeof fetch;
    renderProvider(fetchImpl);

    await screen.findByText("status:ready");
  });

  it("US2-AC8 깨어나기를 기다리던 쪽은 깨어나면 true를 받는다", async () => {
    const fetchImpl = healthSequence("DOWN", "DOWN", "DOWN", "UP");
    renderProvider(fetchImpl);
    await screen.findByText("status:waking");

    const ready = await act(() => captured!.waitUntilReady());
    expect(ready).toBe(true);
    expect(screen.getByText("status:ready")).toBeInTheDocument();
  });

  it("US2-AC8 끝내 깨어나지 않으면 unavailable이 되고 기다리던 쪽은 false를 받는다", async () => {
    const fetchImpl = healthSequence("DOWN");
    renderProvider(fetchImpl, { giveUpAfterMs: 30 });
    await screen.findByText("status:waking");

    const ready = await act(() => captured!.waitUntilReady());
    expect(ready).toBe(false);
    expect(screen.getByText("status:unavailable")).toBeInTheDocument();
  });

  it("US2-AC8 포기한 뒤 다시 기다리면 처음부터 다시 묻는다", async () => {
    let up = false;
    const fetchImpl = vi.fn(() =>
      health(up ? "UP" : "DOWN"),
    ) as unknown as typeof fetch;
    renderProvider(fetchImpl, { giveUpAfterMs: 30 });
    await screen.findByText("status:unavailable");

    up = true;
    const ready = await act(() => captured!.waitUntilReady());
    expect(ready).toBe(true);
  });

  it("US2-AC8 막 확인했으면 다시 묻지 않고, 오래됐으면 다시 묻는다", async () => {
    const fetchImpl = healthSequence("UP");
    renderProvider(fetchImpl, { staleAfterMs: 50 });
    await screen.findByText("status:ready");

    await act(() => captured!.waitUntilReady());
    expect(fetchImpl).toHaveBeenCalledTimes(1);

    await new Promise((resolve) => setTimeout(resolve, 60));
    await act(() => captured!.waitUntilReady());
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("공급자 밖에서는 늘 깨어 있다고 보고 바로 true를 준다", async () => {
    render(<Probe />);
    expect(screen.getByText("status:ready")).toBeInTheDocument();
    await expect(captured!.waitUntilReady()).resolves.toBe(true);
  });

  it("US2-AC7 화면을 떠나면 더 묻지 않는다", async () => {
    const fetchImpl = healthSequence("DOWN");
    const { unmount } = render(
      <ApiReadinessProvider fetchImpl={fetchImpl} pollIntervalMs={10}>
        <Probe />
      </ApiReadinessProvider>,
    );
    await screen.findByText("status:waking");
    unmount();
    const calls = (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls
      .length;
    await new Promise((resolve) => setTimeout(resolve, 50));
    await waitFor(() =>
      expect(
        (fetchImpl as unknown as ReturnType<typeof vi.fn>).mock.calls.length,
      ).toBeLessThanOrEqual(calls + 1),
    );
  });
});
