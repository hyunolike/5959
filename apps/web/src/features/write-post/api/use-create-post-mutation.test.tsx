import {
  QueryClient,
  QueryClientProvider,
  type QueryKey,
} from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useCreatePostMutation } from "./use-create-post-mutation";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useCreatePostMutation", () => {
  it("US1-AC1 글을 올리면 모든 피드를 무효화하고 상세 캐시는 건드리지 않는다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        Response.json(
          { success: true, data: { postId: 9 }, error: null },
          { status: 201 },
        ),
      );
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = new QueryClient();
    const keys: QueryKey[] = [
      ["feed", { order: "LATEST" }],
      ["feed", { order: "POPULAR" }],
      ["posts", 8],
    ];
    keys.forEach((key) => queryClient.setQueryData(key, {}));
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useCreatePostMutation(), { wrapper });
    await act(() =>
      result.current.mutateAsync({
        content: "새 고민",
        commentTone: "COMFORT_ME",
      }),
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts",
      expect.objectContaining({ method: "POST" }),
    );
    const invalidated = (key: QueryKey) =>
      queryClient.getQueryState(key)?.isInvalidated;
    expect(invalidated(["feed", { order: "LATEST" }])).toBe(true);
    expect(invalidated(["feed", { order: "POPULAR" }])).toBe(true);
    expect(invalidated(["posts", 8])).toBe(false);
  });
});
