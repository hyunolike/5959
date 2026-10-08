import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useNicknameCheck } from "./use-nickname-check";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useNicknameCheck isSettled", () => {
  it("디바운스(400ms)가 끝나기 전에는 isSettled가 false다 — 오래된 값에 대한 결과를 현재 입력의 결과로 오인하면 안 된다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        jsonResponse({
          success: true,
          data: { available: false, reason: "TAKEN" },
          error: null,
        }),
      ),
    );

    const { result, rerender } = renderHook(
      ({ nickname }: { nickname: string }) => useNicknameCheck(nickname),
      { wrapper, initialProps: { nickname: "dup1" } },
    );

    await waitFor(() => expect(result.current.data).toBeDefined(), {
      timeout: 2000,
    });
    expect(result.current.isSettled).toBe(true);
    expect(result.current.data).toEqual({
      available: false,
      reason: "TAKEN",
    });

    // 사용자가 "dup1"을 "free1"로 빠르게 고쳐 쓴다. 디바운스가 끝나기 전까지는
    // data가 여전히 "dup1"의 결과이므로 isSettled가 false여야 한다.
    rerender({ nickname: "free1" });
    expect(result.current.isSettled).toBe(false);
  });

  it("타이핑 직후에는 isSettled가 false였다가, 디바운스가 끝나면 true가 되고 그 입력에 대한 결과를 돌려준다", async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL) => {
      const available = !input.toString().includes("dup2");
      return Promise.resolve(
        jsonResponse({
          success: true,
          data: { available, reason: available ? null : "TAKEN" },
          error: null,
        }),
      );
    });
    vi.stubGlobal("fetch", fetchMock);

    const { result, rerender } = renderHook(
      ({ nickname }: { nickname: string }) => useNicknameCheck(nickname),
      { wrapper, initialProps: { nickname: "" } },
    );

    rerender({ nickname: "dup2" });
    expect(result.current.isSettled).toBe(false);

    await waitFor(() => expect(result.current.isSettled).toBe(true), {
      timeout: 2000,
    });
    await waitFor(() =>
      expect(result.current.data).toEqual({
        available: false,
        reason: "TAKEN",
      }),
    );
  });
});
