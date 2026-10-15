import {
  QueryClient,
  QueryClientProvider,
  type QueryKey,
} from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { useDeletePostMutation } from "./use-delete-post-mutation";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("useDeletePostMutation", () => {
  it("FR-014 글을 지우면 피드와 함께 내 글이 있는지도 다시 받는다", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 204 })),
    );
    const queryClient = new QueryClient();
    const keys: QueryKey[] = [
      ["feed", { order: "LATEST" }],
      ["my", "has-post"],
    ];
    keys.forEach((key) => queryClient.setQueryData(key, {}));
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );

    const { result } = renderHook(() => useDeletePostMutation(3), { wrapper });
    await act(() => result.current.mutateAsync());

    const invalidated = (key: QueryKey) =>
      queryClient.getQueryState(key)?.isInvalidated;
    expect(invalidated(["feed", { order: "LATEST" }])).toBe(true);
    expect(invalidated(["my", "has-post"])).toBe(true);
  });
});
