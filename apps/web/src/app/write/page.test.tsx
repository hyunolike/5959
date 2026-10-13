import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/features/write-post", () => ({
  WriteForm: () => <form aria-label="고민 쓰기" />,
}));

import WritePage from "./page";

describe("고민 쓰기 화면", () => {
  it("US3-AC1 글의 앞 50자가 로그인하지 않은 방문자에게도 닉네임 없이 보인다고 알린다", () => {
    render(<WritePage />);
    expect(
      screen.getByText(
        "올린 글의 앞 50자는 로그인하지 않은 방문자에게도 닉네임 없이 보여요.",
      ),
    ).toBeInTheDocument();
  });

  it("US4-AC7 AI 친구가 한 명 이상 답할 수 있다고 미리 알리고 안내 화면으로 간다", () => {
    const { container: page } = render(<WritePage />);

    expect(page).toHaveTextContent(
      "한동안 댓글이 없으면 AI 친구가 한 명에서 세 명까지 먼저 답할 수 있어요.",
    );
    expect(screen.getByRole("link", { name: "AI 친구" })).toHaveAttribute(
      "href",
      "/ai-friends",
    );
  });
});
