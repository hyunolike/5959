import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { EmotionLabels, WeeklyEmotionCount } from "../model/types";
import { WeeklyChart } from "./weekly-chart";

const LABELS: EmotionLabels = {
  ANXIETY: "불안",
  LETHARGY: "무기력",
  LONELINESS: "외로움",
  SELF_DEPRECATION: "자기비하",
  IRRITATION: "짜증",
};

const EMOTIONS = Object.keys(LABELS) as (keyof EmotionLabels)[];

const week = (weekStart: string, counts: number[]): WeeklyEmotionCount => ({
  weekStart,
  counts: EMOTIONS.map((emotion, index) => ({
    emotion,
    count: counts[index],
  })),
});

const WEEKLY = [
  week("2026-08-17", [0, 0, 0, 0, 0]),
  week("2026-08-24", [1, 0, 0, 0, 0]),
  week("2026-08-31", [0, 0, 0, 0, 0]),
  week("2026-09-07", [0, 0, 0, 0, 0]),
  week("2026-09-14", [0, 0, 0, 0, 0]),
  week("2026-09-21", [0, 0, 0, 0, 0]),
  week("2026-09-28", [0, 0, 0, 0, 0]),
  week("2026-10-05", [2, 0, 1, 0, 1]),
];

describe("WeeklyChart", () => {
  it("US4-AC3 8주를 오래된 주부터 막대 8개로 그리고, 빈 주는 높이가 0이다", () => {
    render(<WeeklyChart weekly={WEEKLY} labels={LABELS} />);

    const bars = screen.getAllByTestId("weekly-bar");
    expect(bars).toHaveLength(8);
    // 가장 많은 주(4마리)가 기준이다.
    expect(bars.map((bar) => bar.style.height)).toEqual([
      "0%",
      "18.75%",
      "0%",
      "0%",
      "0%",
      "0%",
      "0%",
      "75%",
    ]);
    // 감정별로 쌓고, 0인 감정은 조각이 없다.
    expect(bars[7].children).toHaveLength(3);
    expect(bars[0].children).toHaveLength(0);
    expect(screen.getByText("8.17")).toBeInTheDocument();
    expect(screen.getByText("10.05")).toBeInTheDocument();
  });

  it("같은 값을 표로도 둔다", () => {
    render(<WeeklyChart weekly={WEEKLY} labels={LABELS} />);

    const table = screen.getByRole("table", {
      name: "최근 8주 감정별 몬스터 수",
    });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(9);
    expect(
      within(rows[0])
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual(["주 시작일", "불안", "무기력", "외로움", "자기비하", "짜증"]);
    expect(
      within(rows[8])
        .getAllByRole("cell")
        .map((cell) => cell.textContent),
    ).toEqual(["2", "0", "1", "0", "1"]);
    expect(within(rows[8]).getByRole("rowheader")).toHaveTextContent(
      "2026-10-05",
    );
  });

  it("몬스터가 하나도 없어도 막대 8개를 높이 0으로 그린다", () => {
    render(
      <WeeklyChart
        weekly={WEEKLY.map((item) => week(item.weekStart, [0, 0, 0, 0, 0]))}
        labels={LABELS}
      />,
    );

    expect(
      screen.getAllByTestId("weekly-bar").map((bar) => bar.style.height),
    ).toEqual(Array(8).fill("0%"));
  });
});
