import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { EmotionTag } from "./emotion-tag";
import { HpBar, hpPercent } from "./hp-bar";
import { MonsterStage } from "./monster-stage";
import { MonsterThumb } from "./monster-thumb";

describe("EmotionTag", () => {
  it("DS-US3-AC1 감정 이름을 보이고 그 감정의 색 범위를 연다", () => {
    render(<EmotionTag emotion="LONELINESS" />);
    const tag = screen.getByText("외로움");
    expect(tag).toHaveAttribute("data-emotion", "LONELINESS");
    expect(tag).toHaveClass("bg-emotion-tint", "text-emotion-fg");
  });
});

describe("hpPercent", () => {
  it.each([
    [62, 100, 62],
    [10, 30, 33],
    [0, 100, 0],
    [100, 100, 100],
  ])("%d / %d는 %d%%다", (hp, maxHp, expected) => {
    expect(hpPercent(hp, maxHp)).toBe(expected);
  });

  it.each([
    [150, 100, 100],
    [-5, 100, 0],
    [10, 0, 0],
    [10, -20, 0],
    [Number.NaN, 100, 0],
    [10, Number.POSITIVE_INFINITY, 0],
  ])("범위를 벗어난 %d / %d는 %d%%로 자른다", (hp, maxHp, expected) => {
    expect(hpPercent(hp, maxHp)).toBe(expected);
  });
});

describe("hpPercent 살아 있는 동안", () => {
  it("DS-US3-AC3 HP가 조금이라도 남으면 0%로 그리지 않는다", () => {
    expect(hpPercent(40, 10000)).toBe(1);
    expect(hpPercent(1, 10000)).toBe(1);
  });

  it("DS-US3-AC3 HP가 조금이라도 깎였으면 100%로 그리지 않는다", () => {
    expect(hpPercent(9999, 10000)).toBe(99);
  });
});

describe("HpBar", () => {
  it("DS-US3-AC1 작은 막대는 6px이고 진행 막대로 읽힌다", () => {
    render(<HpBar emotion="ANXIETY" hp={62} maxHp={100} />);
    const bar = screen.getByRole("progressbar", { name: "몬스터 HP" });
    expect(bar).toHaveAttribute("aria-valuenow", "62");
    expect(bar).toHaveAttribute("aria-valuemax", "100");
    expect(bar).toHaveAttribute("data-emotion", "ANXIETY");
    expect(bar).toHaveClass("h-1.5");
    expect(bar.firstElementChild).toHaveStyle({ width: "62%" });
  });

  it("DS-US3-AC2 큰 막대는 22px에 외곽선이 있다", () => {
    render(<HpBar emotion="IRRITATION" hp={4120} maxHp={10000} size="lg" />);
    expect(screen.getByRole("progressbar")).toHaveClass(
      "h-[22px]",
      "border-2",
      "border-game-line",
    );
  });

  it("최대치를 넘는 HP도 100%를 넘지 않는다", () => {
    render(<HpBar emotion="ANXIETY" hp={150} maxHp={100} />);
    const bar = screen.getByRole("progressbar");
    expect(bar).toHaveAttribute("aria-valuenow", "100");
    expect(bar.firstElementChild).toHaveStyle({ width: "100%" });
  });
});

describe("MonsterThumb", () => {
  it("DS-US3-AC1 48px 자리에 감정의 옅은 색을 깔고 그림을 올린다", () => {
    render(<MonsterThumb emotion="LETHARGY" stage="hurt" />);
    const image = screen.getByRole("img", { name: "무기력 몬스터, 상처 입음" });
    expect(image.parentElement).toHaveAttribute("data-emotion", "LETHARGY");
    expect(image.parentElement).toHaveClass("size-12", "bg-emotion-tint");
  });
});

describe("MonsterStage", () => {
  it("DS-US3-AC2 무대는 그 감정의 옅은 색이다", () => {
    render(
      <MonsterStage emotion="IRRITATION">
        <p>보스</p>
      </MonsterStage>,
    );
    const stage = screen.getByText("보스").parentElement;
    expect(stage).toHaveAttribute("data-emotion", "IRRITATION");
    expect(stage).toHaveClass("bg-emotion-tint");
  });
});
