import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { MonsterCard } from "./monster-card";
import { MonsterDisplay } from "./monster-view";
import { MonsterSummary } from "./monster-summary";

const alive = (hp: number) => ({
  emotion: "ANXIETY" as const,
  hp,
  maxHp: 10 as const,
  status: "ALIVE" as const,
});

const srcOf = (img: HTMLElement) =>
  decodeURIComponent(img.getAttribute("src") ?? "");

describe("글 번호로 고른 불안 몬스터의 모습(017)", () => {
  it("US1-AC1 목록의 몬스터는 글 번호로 모습을 고른다", () => {
    render(<MonsterSummary monster={alive(10)} postId={101} />);
    expect(srcOf(screen.getByRole("img"))).toContain("anxiety-v2-full");
  });

  it("US1-AC2 같은 글은 목록과 상세에서 같은 모습이다", () => {
    const { unmount } = render(
      <MonsterSummary monster={alive(10)} postId={102} />,
    );
    const inList = srcOf(screen.getByRole("img"));
    unmount();
    render(
      <MonsterDisplay monster={alive(10)} variant="detail" postId={102} />,
    );

    expect(srcOf(screen.getByRole("img"))).toContain("anxiety-v3-full");
    expect(inList).toContain("anxiety-v3-full");
  });

  it("US1-AC3 HP가 줄면 같은 모습의 단계 그림으로 바뀐다", () => {
    const { rerender } = render(
      <MonsterDisplay monster={alive(10)} variant="detail" postId={103} />,
    );
    rerender(
      <MonsterDisplay monster={alive(2)} variant="detail" postId={103} />,
    );
    expect(srcOf(screen.getByRole("img"))).toContain("anxiety-v4-weak");
  });

  it("US1-AC4 글 번호를 넘기지 않으면 지금 그림이다", () => {
    render(<MonsterSummary monster={alive(10)} />);
    expect(srcOf(screen.getByRole("img"))).toContain("/monsters/anxiety-full");
  });

  it("US1-AC5 새 모습도 대체 텍스트는 그대로다", () => {
    render(<MonsterSummary monster={alive(10)} postId={101} />);
    expect(
      screen.getByRole("img", { name: "불안 몬스터, 멀쩡함" }),
    ).toBeInTheDocument();
  });

  it("US2-AC1 도감 카드는 그 글의 모습으로 쓰러져 있다", () => {
    const { container } = render(
      <MonsterCard
        card={{
          cardNo: 7,
          emotion: "ANXIETY",
          grade: "COMMON",
          role: "MINE",
          defeatedAt: "2026-10-15T00:00:00Z",
          myDamage: 0,
          helperCount: 3,
          postId: 101,
        }}
      />,
    );
    const img = container.querySelector("img");
    expect(srcOf(img as HTMLElement)).toContain("anxiety-v2-defeated");
  });
});
