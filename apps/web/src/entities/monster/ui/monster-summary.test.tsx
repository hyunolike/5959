import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MonsterSummary } from "./monster-summary";

describe("MonsterSummary", () => {
  it("DS-US3-AC1 48px 몬스터와 HP 숫자, HP 막대를 보인다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "ANXIETY", hp: 5, maxHp: 10, status: "ALIVE" }}
      />,
    );
    const image = screen.getByRole("img", { name: "불안 몬스터, 상처 입음" });
    expect(image.parentElement).toHaveClass("size-12");
    expect(screen.getByText("HP 5/10")).toBeInTheDocument();
    expect(
      screen.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "5");
  });

  it("DS-US3-AC1 처치된 몬스터는 쓰러진 그림과 처치됨으로 보인다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "LETHARGY", hp: 0, maxHp: 10, status: "DEFEATED" }}
      />,
    );
    expect(
      screen.getByRole("img", { name: "무기력 몬스터, 쓰러짐" }),
    ).toBeInTheDocument();
    expect(screen.getByText("처치됨")).toBeInTheDocument();
    expect(screen.getByText("HP 0/10")).toBeInTheDocument();
  });

  it("최대 HP가 0으로 와도 그린다", () => {
    render(
      <MonsterSummary
        monster={{ emotion: "IRRITATION", hp: 0, maxHp: 0, status: "ALIVE" }}
      />,
    );
    expect(screen.getByRole("progressbar").firstElementChild).toHaveStyle({
      width: "0%",
    });
  });
});
