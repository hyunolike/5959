import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { GameButton } from "./game-button";

describe("GameButton", () => {
  it("DS-US3-AC2 노란 바탕에 외곽선과 단색 그림자가 있다", () => {
    render(<GameButton>공격하기</GameButton>);
    const button = screen.getByRole("button", { name: "공격하기" });
    expect(button).toHaveClass(
      "bg-game",
      "border-game-line",
      "shadow-game",
      "min-h-14",
    );
    expect(button).toHaveAttribute("type", "button");
  });

  it("DS-US4-AC3 움직임 줄이기에서는 눌러도 내려가지 않는다", () => {
    render(<GameButton>공격하기</GameButton>);
    expect(screen.getByRole("button", { name: "공격하기" })).toHaveClass(
      "motion-reduce:active:translate-y-0",
    );
  });

  it("잠기면 그림자가 사라진다", () => {
    render(<GameButton disabled>공격하기</GameButton>);
    const button = screen.getByRole("button", { name: "공격하기" });
    expect(button).toBeDisabled();
    expect(button).toHaveClass("disabled:shadow-none");
  });

  it("DS-US3-AC2 눌린 상태(aria-pressed)는 내려가 있고 그림자가 없다", () => {
    render(<GameButton aria-pressed>공감 3</GameButton>);
    expect(screen.getByRole("button", { name: "공감 3" })).toHaveClass(
      "aria-pressed:translate-y-1",
      "aria-pressed:shadow-none",
    );
  });

  it("요청이 가는 동안(disabled와 aria-busy)에는 회색으로 깜빡이지 않는다", () => {
    render(
      <GameButton disabled aria-busy>
        공감 3
      </GameButton>,
    );
    expect(screen.getByRole("button", { name: "공감 3" })).toHaveClass(
      "disabled:aria-busy:bg-game",
      "disabled:aria-busy:text-fg",
      "disabled:aria-busy:border-game-line",
    );
  });

  it("DS-US3-AC2 눌리지 않은 버튼은 요청이 가는 동안에도 그림자를 유지한다", () => {
    render(
      <GameButton disabled aria-busy>
        공감 3
      </GameButton>,
    );
    expect(screen.getByRole("button", { name: "공감 3" })).toHaveClass(
      "disabled:aria-busy:not-aria-pressed:shadow-game",
    );
  });

  it("DS-US4-AC3 움직임 줄이기에서는 눌린 상태도 내려가 있지 않는다", () => {
    render(<GameButton aria-pressed>공감 3</GameButton>);
    expect(screen.getByRole("button", { name: "공감 3" })).toHaveClass(
      "motion-reduce:aria-pressed:translate-y-0",
    );
  });
});
