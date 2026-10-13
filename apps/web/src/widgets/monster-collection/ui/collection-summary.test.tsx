import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

import type { MonsterCardSummary } from "@/entities/monster";

import { CollectionSummary } from "./collection-summary";

const SUMMARY: MonsterCardSummary = {
  total: 3,
  byEmotion: [
    { emotion: "ANXIETY", count: 2 },
    { emotion: "LETHARGY", count: 0 },
    { emotion: "LONELINESS", count: 0 },
    { emotion: "SELF_DEPRECATION", count: 0 },
    { emotion: "IRRITATION", count: 1 },
  ],
};

describe("CollectionSummary", () => {
  it("US4-AC1 모은 수와 다섯 감정의 장수를 보인다", () => {
    render(
      <CollectionSummary
        summary={SUMMARY}
        selected={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText("모두 3장")).toBeInTheDocument();
    const group = screen.getByRole("group", { name: "감정으로 골라 보기" });
    expect(within(group).getByRole("button", { name: "불안 2" })).toBeVisible();
    expect(within(group).getByRole("button", { name: "짜증 1" })).toBeVisible();
    for (const name of ["무기력 0", "외로움 0", "자기비하 0"]) {
      expect(within(group).getByText(name)).toBeVisible();
    }
  });

  it("US4-AC2 한 장도 없는 감정은 누를 수 없는 빈 자리이고 글로도 알린다", () => {
    render(
      <CollectionSummary
        summary={SUMMARY}
        selected={null}
        onSelect={vi.fn()}
      />,
    );

    expect(
      screen.queryByRole("button", { name: "무기력 0" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("무기력 0")).toHaveClass("border-dashed");
    expect(
      screen.getByText("아직 만나지 못한 몬스터: 무기력, 외로움, 자기비하"),
    ).toBeVisible();
  });

  it("US4-AC3 감정을 누르면 고르고 다시 누르면 푼다", async () => {
    const user = userEvent.setup();
    const onSelect = vi.fn();
    const { rerender } = render(
      <CollectionSummary
        summary={SUMMARY}
        selected={null}
        onSelect={onSelect}
      />,
    );

    await user.click(screen.getByRole("button", { name: "불안 2" }));
    expect(onSelect).toHaveBeenLastCalledWith("ANXIETY");

    rerender(
      <CollectionSummary
        summary={SUMMARY}
        selected="ANXIETY"
        onSelect={onSelect}
      />,
    );
    const pressed = screen.getByRole("button", { name: "불안 2" });
    expect(pressed).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "짜증 1" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );

    await user.click(pressed);
    expect(onSelect).toHaveBeenLastCalledWith(null);
  });

  it("US4-AC4 감정의 이름과 숫자가 글자로 읽히고 색 점은 낭독하지 않는다", () => {
    render(
      <CollectionSummary
        summary={SUMMARY}
        selected={null}
        onSelect={vi.fn()}
      />,
    );

    const chip = screen.getByRole("button", { name: "불안 2" });
    expect(chip).toHaveTextContent("불안 2");
    expect(chip.querySelector("[aria-hidden]")).not.toBeNull();
  });

  it("모든 감정을 모았으면 못 만난 몬스터 안내가 없다", () => {
    render(
      <CollectionSummary
        summary={{
          total: 5,
          byEmotion: SUMMARY.byEmotion.map((item) => ({ ...item, count: 1 })),
        }}
        selected={null}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByText(/아직 만나지 못한/)).not.toBeInTheDocument();
  });
});
