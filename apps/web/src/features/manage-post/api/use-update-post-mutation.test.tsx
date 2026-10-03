import {
  QueryClient,
  QueryClientProvider,
  type QueryKey,
} from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useUpdatePostMutation } from "./use-update-post-mutation";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useUpdatePostMutation", () => {
  it("US4-AC1 PATCH /api/posts/{id}로 본문과 말투를 보내고 상세와 모든 피드를 무효화한다", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    const queryClient = new QueryClient();
    const keys: QueryKey[] = [
      ["posts", 7],
      ["posts", 7, "comments"],
      ["feed", { order: "POPULAR" }],
      ["posts", 8],
    ];
    keys.forEach((key) => queryClient.setQueryData(key, {}));
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useUpdatePostMutation(7), { wrapper });
    await act(() =>
      result.current.mutateAsync({
        content: "고친 고민",
        commentTone: "MAKE_ME_LAUGH",
      }),
    );

    expect(fetchMock).toHaveBeenCalledWith(
      "/api/posts/7",
      expect.objectContaining({
        method: "PATCH",
        body: JSON.stringify({
          content: "고친 고민",
          commentTone: "MAKE_ME_LAUGH",
        }),
      }),
    );
    const invalidated = (key: QueryKey) =>
      queryClient.getQueryState(key)?.isInvalidated;
    expect(invalidated(["posts", 7])).toBe(true);
    expect(invalidated(["feed", { order: "POPULAR" }])).toBe(true);
    expect(invalidated(["posts", 8])).toBe(false);
  });
});
