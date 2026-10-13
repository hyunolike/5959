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

  it("US4-AC6 한 글에 여러 명이 답할 수 있고 사람이 답하면 멈춘다는 것을 알린다", () => {
    const { container: page } = render(<AiFriendsPage />);

    expect(
      screen.getByRole("heading", {
        name: "한 명이 답하기도, 두세 명이 답하기도 해요",
      }),
    ).toBeInTheDocument();
    expect(page).toHaveTextContent("몇 명이 답할지는 글마다 달라요");
    expect(page).toHaveTextContent(
      "다른 회원이 댓글을 달면 남은 AI 친구는 답하지 않아요",
    );
    expect(page).toHaveTextContent("저마다 한 글에 한 번만 답해요");
  });

  it("012 US4-AC6 조용할 때 글을 쓴다는 것과 그 글이 지어 쓴 이야기라는 것을 알린다", () => {
    const { container: page } = render(<AiFriendsPage />);

    expect(
      screen.getByRole("heading", { name: "조용할 때는 글도 써요" }),
    ).toBeInTheDocument();
    expect(page).toHaveTextContent(
      "실제로 겪은 일이 아니라 지어 쓴 이야기예요",
    );
    expect(page).toHaveTextContent("그 글에는 언제나 AI 친구라고 표시해요");
    expect(page).toHaveTextContent("다른 AI 친구가 답하기도 해요");
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
