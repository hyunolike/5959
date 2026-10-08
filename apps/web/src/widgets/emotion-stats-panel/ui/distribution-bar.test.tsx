import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { EmotionLabels, EmotionShare } from "../model/types";
import { DistributionBar } from "./distribution-bar";

const LABELS: EmotionLabels = {
  ANXIETY: "불안",
  LETHARGY: "무기력",
  LONELINESS: "외로움",
  SELF_DEPRECATION: "자기비하",
  IRRITATION: "짜증",
};

const distribution = (
  values: [count: number, percent: number][],
): EmotionShare[] =>
  (Object.keys(LABELS) as (keyof EmotionLabels)[]).map((emotion, index) => ({
    emotion,
    count: values[index][0],
    percent: values[index][1],
  }));

describe("DistributionBar", () => {
  it("US4-AC1 감정 5종을 고정 순서로 모두 보이고 수와 비율을 적는다", () => {
    render(
      <DistributionBar
        distribution={distribution([
          [2, 67],
          [0, 0],
          [0, 0],
          [0, 0],
          [1, 33],
        ])}
        labels={LABELS}
      />,
    );

    const items = within(
      screen.getByRole("list", { name: "감정 분포" }),
    ).getAllByRole("listitem");
    expect(items.map((item) => item.textContent)).toEqual([
      "불안2마리67%",
      "무기력0마리0%",
      "외로움0마리0%",
      "자기비하0마리0%",
      "짜증1마리33%",
    ]);
  });

  it("막대는 비율이 있는 감정만 그 비율만큼 차지한다", () => {
    render(
      <DistributionBar
        distribution={distribution([
          [2, 67],
          [0, 0],
          [0, 0],
          [0, 0],
          [1, 33],
        ])}
        labels={LABELS}
      />,
    );

    const segments = screen.getAllByTestId("distribution-segment");
    expect(segments.map((segment) => segment.style.width)).toEqual([
      "67%",
      "33%",
    ]);
    expect(segments.map((segment) => segment.title)).toEqual([
      "불안 67%",
      "짜증 33%",
    ]);
  });

  it("US4-AC4 몬스터가 없으면 막대는 비고 범례는 모두 0이다", () => {
    render(
      <DistributionBar
        distribution={distribution([
          [0, 0],
          [0, 0],
          [0, 0],
          [0, 0],
          [0, 0],
        ])}
        labels={LABELS}
      />,
    );

    expect(screen.queryAllByTestId("distribution-segment")).toHaveLength(0);
    expect(screen.getAllByText("0%")).toHaveLength(5);
  });
});
