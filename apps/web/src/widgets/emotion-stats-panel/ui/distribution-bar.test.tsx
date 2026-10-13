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

  it("DS-US1-AC3 막대의 조각과 범례의 점은 감정마다 같은 색이고, 글자는 감정 색을 입지 않는다", () => {
    render(
      <DistributionBar
        distribution={distribution([
          [2, 40],
          [1, 20],
          [1, 20],
          [1, 20],
          [0, 0],
        ])}
        labels={LABELS}
      />,
    );
    const segments = screen.getAllByTestId("distribution-segment");
    const items = within(
      screen.getByRole("list", { name: "감정 분포" }),
    ).getAllByRole("listitem");
    segments.forEach((segment, index) => {
      const dot = items[index].querySelector("[aria-hidden]") as HTMLElement;
      expect(dot.style.backgroundColor).toBe(segment.style.backgroundColor);
      expect(segment.style.backgroundColor).not.toBe("");
    });
    for (const item of items) {
      for (const text of item.querySelectorAll("span:not([aria-hidden])")) {
        expect((text as HTMLElement).style.color).toBe("");
        expect(text.className).not.toContain("emotion");
      }
    }
  });

  it("DS-US3-AC6 조각 사이는 2px 띄우고 막대의 양 끝만 둥글다", () => {
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
    const bar = screen.getAllByTestId("distribution-segment")[0]
      .parentElement as HTMLElement;
    expect(bar).toHaveClass("gap-0.5", "rounded-full", "overflow-hidden");
  });

  it("US4-AC1 한 감정만 있어도 막대가 그 한 조각으로 그려진다", () => {
    render(
      <DistributionBar
        distribution={distribution([
          [4, 100],
          [0, 0],
          [0, 0],
          [0, 0],
          [0, 0],
        ])}
        labels={LABELS}
      />,
    );
    const segments = screen.getAllByTestId("distribution-segment");
    expect(segments).toHaveLength(1);
    expect(segments[0]).toHaveStyle({ width: "100%" });
    expect(
      within(screen.getByRole("list", { name: "감정 분포" })).getAllByRole(
        "listitem",
      )[0],
    ).toHaveTextContent("불안4마리100%");
  });
});
