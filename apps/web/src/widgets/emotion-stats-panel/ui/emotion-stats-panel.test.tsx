import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { EmotionStatsPanel } from "./emotion-stats-panel";

const EMOTIONS = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
] as const;

function stats(overrides: Record<string, unknown> = {}) {
  const counts = [2, 0, 0, 0, 1];
  const percents = [67, 0, 0, 0, 33];
  return {
    totalMonsters: 3,
    defeatedMonsters: 1,
    defeatedTogether: 4,
    distribution: EMOTIONS.map((emotion, index) => ({
      emotion,
      count: counts[index],
      percent: percents[index],
    })),
    topEmotion: "ANXIETY",
    weekly: Array.from({ length: 8 }, (_, week) => ({
      weekStart: `2026-08-${String(3 + week * 7).padStart(2, "0")}`,
      counts: EMOTIONS.map((emotion, index) => ({
        emotion,
        count: week === 7 ? counts[index] : 0,
      })),
    })),
    ...overrides,
  };
}

const EMPTY = stats({
  totalMonsters: 0,
  defeatedMonsters: 0,
  defeatedTogether: 0,
  topEmotion: null,
  distribution: EMOTIONS.map((emotion) => ({ emotion, count: 0, percent: 0 })),
});

function stubStats(data: unknown, status = 200) {
  const fetchMock = vi.fn().mockResolvedValue(
    new Response(
      JSON.stringify(
        status === 200
          ? { success: true, data, error: null }
          : {
              success: false,
              data: null,
              error: { code: "INTERNAL_ERROR", message: "실패" },
            },
      ),
      { status, headers: { "content-type": "application/json" } },
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderPanel() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <EmotionStatsPanel />
    </QueryClientProvider>,
  );
}

const figure = (label: string) =>
  screen.getByText(label).closest("div") as HTMLElement;

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("EmotionStatsPanel", () => {
  it("US4-AC1 전체와 물리친 몬스터 수, 감정 분포를 보인다", async () => {
    const fetchMock = stubStats(stats());

    renderPanel();

    const panel = await screen.findByRole("region", { name: "감정 통계" });
    await within(panel).findByText("내 몬스터");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/members/me/emotion-stats",
      expect.objectContaining({ cache: "no-store" }),
    );
    expect(figure("내 몬스터")).toHaveTextContent("3");
    expect(figure("물리친 몬스터")).toHaveTextContent("1");
    const shares = within(
      within(panel).getByRole("list", { name: "감정 분포" }),
    ).getAllByRole("listitem");
    expect(shares).toHaveLength(5);
    expect(shares[0]).toHaveTextContent("불안2마리67%");
    expect(within(panel).getAllByTestId("weekly-bar")).toHaveLength(8);
  });

  it("US4-AC2 가장 많이 나타난 몬스터를 이름과 정지 이미지로 보인다", async () => {
    stubStats(stats({ topEmotion: "IRRITATION" }));

    renderPanel();

    const label = await screen.findByText("가장 많이 나타난 몬스터");
    expect(label).toHaveTextContent("짜증");
    expect(
      screen.getByRole("img", { name: "짜증 몬스터, 멀쩡함" }),
    ).toBeInTheDocument();
  });

  it('US4-AC4 몬스터가 없으면 숫자는 0이고 "아직 몬스터가 없어요"와 글쓰기 안내가 보인다', async () => {
    stubStats(EMPTY);

    renderPanel();

    expect(await screen.findByText(/아직 몬스터가 없어요/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "고민 쓰기" })).toHaveAttribute(
      "href",
      "/write",
    );
    expect(figure("내 몬스터")).toHaveTextContent("0");
    expect(figure("물리친 몬스터")).toHaveTextContent("0");
    expect(figure("함께 물리친 몬스터")).toHaveTextContent("0");
    expect(screen.queryByText("가장 많이 나타난 몬스터")).toBeNull();
    expect(screen.queryByRole("list", { name: "감정 분포" })).toBeNull();
    expect(screen.queryAllByTestId("weekly-bar")).toHaveLength(0);
  });

  it("US4-AC5 함께 물리친 몬스터 수를 보이고, 내 몬스터가 없어도 보인다", async () => {
    stubStats({ ...EMPTY, defeatedTogether: 2 });

    renderPanel();

    await screen.findByText("함께 물리친 몬스터");
    expect(figure("함께 물리친 몬스터")).toHaveTextContent("2");
    expect(screen.getByText(/아직 몬스터가 없어요/)).toBeInTheDocument();
  });

  it("불러오지 못하면 안내를 보인다", async () => {
    stubStats(null, 500);

    renderPanel();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "감정 통계를 불러오지 못했습니다.",
    );
  });
});
