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
  it("DS-US3-AC1 값은 퍼센트가 아니라 HP 숫자로 읽힌다", () => {
    render(<HpBar emotion="ANXIETY" hp={62} maxHp={100} label="보스 HP" />);
    expect(
      screen.getByRole("progressbar", { name: "보스 HP" }),
    ).toHaveAttribute("aria-valuetext", "HP 62/100");
  });

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

  it("DS-US3-AC2 감정이 아직 없는 무대는 감정 색 범위를 열지 않는다", () => {
    render(
      <MonsterStage>
        <p>분석 중</p>
      </MonsterStage>,
    );
    const stage = screen.getByText("분석 중").parentElement;
    expect(stage).not.toHaveAttribute("data-emotion");
    expect(stage).toHaveClass("bg-emotion-tint");
  });

  it("DS-US3-AC3 무대는 role과 이름을 넘겨받아 구역이 될 수 있다", () => {
    render(
      <MonsterStage emotion="IRRITATION" role="region" aria-label="보스">
        <p>보스</p>
      </MonsterStage>,
    );
    expect(screen.getByRole("region", { name: "보스" })).toHaveAttribute(
      "data-emotion",
      "IRRITATION",
    );
  });

  it("DS-US1-AC3 계약에 없는 감정 값이 와도 태그에 이름이 비지 않는다", () => {
    render(<EmotionTag emotion={"UNKNOWN" as never} />);
    expect(screen.getByText("감정")).toHaveAttribute("data-emotion", "UNKNOWN");
  });

  it("DS-US1-AC2 작은 HP 막대는 채움의 끝에 글자 색 선을 둬 옅은 감정 색에서도 남은 양이 보인다", () => {
    const { rerender } = render(
      <HpBar emotion="LONELINESS" hp={6} maxHp={10} />,
    );
    const fill = () => screen.getByRole("progressbar").firstElementChild;
    expect(fill()).toHaveClass("border-r-2", "border-emotion-fg");

    rerender(<HpBar emotion="LONELINESS" hp={10} maxHp={10} />);
    expect(fill()).not.toHaveClass("border-r-2");
    rerender(<HpBar emotion="LONELINESS" hp={0} maxHp={10} />);
    expect(fill()).not.toHaveClass("border-r-2");
  });

  it("DS-US1-AC3 계약에 없는 감정이면 그림의 이름도 undefined가 되지 않는다", () => {
    render(<MonsterThumb emotion={"UNKNOWN" as never} stage="full" />);
    expect(screen.getByRole("img").getAttribute("alt")).toBe(
      "감정 몬스터, 멀쩡함",
    );
  });
});
