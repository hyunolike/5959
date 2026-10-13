import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import AiFriendsPage, { metadata } from "./page";

describe("AI 친구 안내", () => {
  it("US2-AC4 AI 친구가 무엇인지 네 가지를 알린다", () => {
    const { container: page } = render(<AiFriendsPage />);

    expect(
      screen.getByRole("heading", { level: 1, name: "AI 친구" }),
    ).toBeInTheDocument();
    // AI가 쓴 글, 반응 없는 글에 먼저 답함, 답글에는 답하지 못함, 전문 상담이 아님
    expect(page).toHaveTextContent("AI가 쓴 댓글이에요");
    expect(page).toHaveTextContent("댓글이 없는 글에 먼저 답해요");
    expect(page).toHaveTextContent("답글에는 답하지 못해요");
    expect(page).toHaveTextContent("전문 상담이 아니에요");
    expect(metadata.title).toBe("AI 친구");
  });

  it("US2-AC4 세 친구의 이름과 도움받을 곳으로 가는 길이 있다", () => {
    render(<AiFriendsPage />);

    for (const name of ["다정한 오구", "씩씩한 오구", "차분한 오구"]) {
      expect(screen.getByText(name)).toBeInTheDocument();
    }
    // 상담 전화 링크는 이름에 기관 이름이 들어가고 44px이다.
    const call = screen.getByRole("link", { name: "자살예방 상담전화 109" });
    expect(call).toHaveAttribute("href", "tel:109");
    expect(call).toHaveClass("min-h-11");
    expect(screen.getByRole("link", { name: "피드로" })).toHaveAttribute(
      "href",
      "/home",
    );
  });

  it("US2-AC4 감정 색과 게임 요소를 쓰지 않는다", () => {
    const { container } = render(<AiFriendsPage />);
    expect(container.innerHTML).not.toMatch(/emotion|bg-game|shadow-game/);
  });
});
