import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import type { MonsterCard as MonsterCardData } from "../model/card";
import { MonsterCard } from "./monster-card";

function card(overrides: Partial<MonsterCardData> = {}): MonsterCardData {
  return {
    cardNo: 128,
    emotion: "ANXIETY",
    grade: "LEGENDARY",
    role: "MINE",
    defeatedAt: "2026-10-12T03:00:00Z",
    myDamage: 0,
    helperCount: 6,
    postId: 10,
    ...overrides,
  };
}

describe("MonsterCard", () => {
  it("US2-AC1 카드에 처치된 몬스터의 그림, 감정, 번호, 등급, 물리친 날이 있다", () => {
    const { container } = render(<MonsterCard card={card()} />);

    expect(screen.getByText("불안")).toBeInTheDocument();
    expect(screen.getByText("No.128")).toBeInTheDocument();
    expect(screen.getByText("전설")).toBeInTheDocument();
    expect(screen.getByText("10월 12일")).toBeInTheDocument();
    expect(screen.getByText("내 몬스터")).toBeInTheDocument();
    const image = container.querySelector("img");
    expect(decodeURIComponent(image?.getAttribute("src") ?? "")).toContain(
      "/monsters/anxiety-defeated.webp",
    );
    // 감정 이름이 글자로 있으므로 그림은 낭독하지 않는다.
    expect(image).toHaveAttribute("alt", "");
  });

  it.each([
    ["COMMON", "일반"],
    ["RARE", "희귀"],
    ["LEGENDARY", "전설"],
  ] as const)(
    "US2-AC2, US3-AC3 등급 %s는 글자로 적히고 data-grade로 반짝임을 가른다",
    (grade, label) => {
      const { container } = render(<MonsterCard card={card({ grade })} />);

      expect(screen.getByText(label)).toBeInTheDocument();
      expect(container.firstElementChild).toHaveAttribute("data-grade", grade);
      expect(container.firstElementChild).toHaveClass("ogu-card");
    },
  );

  it("US2-AC1 감정 색은 data-emotion으로 준다", () => {
    const { container } = render(
      <MonsterCard card={card({ emotion: "IRRITATION" })} />,
    );

    expect(container.firstElementChild).toHaveAttribute(
      "data-emotion",
      "IRRITATION",
    );
    expect(container.firstElementChild).toHaveClass("bg-emotion");
    expect(screen.getByText("짜증")).toBeInTheDocument();
  });

  it("US2-AC3 크게 본 함께 물리친 카드는 내가 준 피해를 보인다", () => {
    render(
      <MonsterCard
        size="lg"
        card={card({ role: "TOGETHER", myDamage: 4, helperCount: null })}
      />,
    );

    expect(screen.getByText("함께 물리침")).toBeInTheDocument();
    expect(screen.getByText("내가 준 피해")).toBeInTheDocument();
    expect(screen.getByText("4")).toBeInTheDocument();
    expect(screen.queryByText("함께 물리친 회원")).not.toBeInTheDocument();
  });

  it("US2-AC4 크게 본 내 몬스터의 카드는 함께 물리친 회원 수를 보인다", () => {
    render(<MonsterCard size="lg" card={card()} />);

    expect(screen.getByText("함께 물리친 회원")).toBeInTheDocument();
    expect(screen.getByText("6명")).toBeInTheDocument();
    expect(screen.queryByText("내가 준 피해")).not.toBeInTheDocument();
    expect(screen.getByText("2026년 10월 12일에 물리침")).toBeInTheDocument();
  });

  it("US3-AC3 글자는 반짝임 위에 그려 빛에 물들지 않는다", () => {
    render(<MonsterCard size="lg" card={card()} />);

    for (const text of ["불안", "No.128", "전설", "내 몬스터", "6명"]) {
      expect(screen.getByText(text).closest(".ogu-card-text")).not.toBeNull();
    }
  });

  it("US3-AC3 반짝임은 장식이라 낭독하지 않는다", () => {
    const { container } = render(<MonsterCard card={card()} />);

    expect(container.querySelector(".ogu-card-shine")).toHaveAttribute(
      "aria-hidden",
      "true",
    );
  });
});
