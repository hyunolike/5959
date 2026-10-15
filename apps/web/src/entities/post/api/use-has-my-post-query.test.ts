import { QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createQueryClient } from "@/shared/api";

import { fetchHasMyPost, useHasMyPostQuery } from "./use-has-my-post-query";

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });

const pageWith = (items: unknown[]) =>
  jsonResponse({
    success: true,
    data: { items, nextCursor: null },
    error: null,
  });

describe("fetchHasMyPost", () => {
  it("FR-012 내 글 목록을 한 개만 받는다", async () => {
    const fetchImpl = vi.fn().mockResolvedValue(pageWith([]));

    await fetchHasMyPost(fetchImpl);

    expect(fetchImpl).toHaveBeenCalledWith(
      "/api/members/me/posts?size=1",
      expect.objectContaining({ cache: "no-store" }),
    );
  });

  it("FR-012 항목이 있으면 true, 없으면 false", async () => {
    await expect(
      fetchHasMyPost(vi.fn().mockResolvedValue(pageWith([{ postId: 1 }]))),
    ).resolves.toBe(true);
    await expect(
      fetchHasMyPost(vi.fn().mockResolvedValue(pageWith([]))),
    ).resolves.toBe(false);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

function wrapper({ children }: { children: ReactNode }) {
  return createElement(
    QueryClientProvider,
    { client: createQueryClient() },
    children,
  );
}

describe("useHasMyPostQuery", () => {
  it("US2-AC9 받는 중이면 undefined다", () => {
    vi.stubGlobal("fetch", vi.fn().mockReturnValue(new Promise(() => {})));

    const { result } = renderHook(() => useHasMyPostQuery(), { wrapper });

    expect(result.current).toBeUndefined();
  });

  it("US2-AC9 실패하면 undefined로 남는다", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(
        {
          success: false,
          data: null,
          error: { code: "INTERNAL_ERROR", message: "잠시 후 다시" },
        },
        500,
      ),
    );
    vi.stubGlobal("fetch", fetchMock);

    const { result } = renderHook(() => useHasMyPostQuery(), { wrapper });

    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    expect(result.current).toBeUndefined();
  });

  it("US2-AC6 내 글이 없으면 false다", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(pageWith([])));

    const { result } = renderHook(() => useHasMyPostQuery(), { wrapper });

    await waitFor(() => expect(result.current).toBe(false));
  });
});
