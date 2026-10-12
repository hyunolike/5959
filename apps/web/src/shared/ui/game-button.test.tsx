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
});
