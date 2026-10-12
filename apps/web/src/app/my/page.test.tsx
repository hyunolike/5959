import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";

import MyPage from "./page";

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace: vi.fn() }) }));
vi.mock("@/widgets/emotion-stats-panel", () => ({
  EmotionStatsPanel: () => null,
}));
vi.mock("@/widgets/my-activity", () => ({ MyActivity: () => null }));

afterEach(() => vi.unstubAllGlobals());

it("UX-US2-AC1 프로필 조회 실패는 끝없는 로딩 대신 복구 안내를 보여 준다", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async () =>
        new Response(
          JSON.stringify({
            success: false,
            data: null,
            error: {
              code: "UNAVAILABLE",
              message: "잠시 후 다시 시도해주세요.",
            },
          }),
          { status: 503, headers: { "content-type": "application/json" } },
        ),
    ),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MyPage />
    </QueryClientProvider>,
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "프로필을 불러오지 못했습니다",
  );
  expect(
    screen.queryByRole("status", { name: "Loading" }),
  ).not.toBeInTheDocument();
});
