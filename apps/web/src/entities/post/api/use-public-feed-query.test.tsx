import { QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ApiError, createQueryClient } from "@/shared/api";

import { fetchPublicFeed, usePublicFeedQuery } from "./use-public-feed-query";

const jsonResponse = (body: unknown, status = 200) =>
  ({ status, json: () => Promise.resolve(body) }) as unknown as Response;

describe("fetchPublicFeed", () => {
  it("US1-AC1 로그인 없이 부르는 경로에서 목록을 받는다", async () => {
    const feed = { items: [] };
    const fetchImpl = vi
      .fn()
      .mockResolvedValue(
        jsonResponse({ success: true, data: feed, error: null }),
      );

    await expect(fetchPublicFeed(fetchImpl)).resolves.toEqual(feed);
    expect(fetchImpl).toHaveBeenCalledWith("/api/public/feed", {
      cache: "no-store",
    });
  });

  it("US1-AC7 실패하면 상태 코드와 함께 던진다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "잠시 후 다시" },
        },
        502,
      ),
    );

    await expect(fetchPublicFeed(fetchImpl)).rejects.toEqual(
      new ApiError(502, { code: "INTERNAL_ERROR", message: "잠시 후 다시" }),
    );
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("usePublicFeedQuery", () => {
  it("US1-AC7 API가 401을 줘도 방문자를 로그인 화면으로 보내지 않고 실패로 남는다", async () => {
    // 웹이 먼저 배포되면 옛 API는 모르는 경로에 401을 준다.
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        status: 401,
        json: () =>
          Promise.resolve({
            success: false,
            data: null,
            error: { code: "UNAUTHORIZED", message: "인증이 필요합니다" },
          }),
      }),
    );
    const onUnauthorized = vi.fn();
    const client = createQueryClient(onUnauthorized);
    const { result } = renderHook(() => usePublicFeedQuery(), {
      wrapper: ({ children }: { children: ReactNode }) => (
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      ),
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(onUnauthorized).not.toHaveBeenCalled();
  });
});
