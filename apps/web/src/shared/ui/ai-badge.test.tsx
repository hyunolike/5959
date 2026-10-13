import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { AI_FRIENDS_PATH, AiBadge } from "./ai-badge";

describe("AiBadge", () => {
  it("US2-AC1 AI 친구라는 것을 글자로 보이고 안내 화면으로 간다", () => {
    render(<AiBadge />);

    const badge = screen.getByRole("link", { name: "AI 친구" });
    expect(badge).toHaveTextContent("AI 친구");
    expect(badge).toHaveAttribute("href", AI_FRIENDS_PATH);
    expect(AI_FRIENDS_PATH).toBe("/ai-friends");
  });

  it("US2-AC1 감정 색과 게임 요소 없이 그리고 누르는 영역을 44px로 넓힌다", () => {
    render(<AiBadge />);

    const badge = screen.getByRole("link", { name: "AI 친구" });
    expect(badge.className).not.toMatch(/emotion|bg-game|shadow/);
    expect(badge).toHaveClass("border-line-strong", "text-fg-muted");
    // 글자 높이 20px에 위아래 12px씩 넓힌다.
    expect(badge).toHaveClass("after:-inset-y-3");
  });
});
