import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("@/features/write-post", () => ({
  WriteForm: () => <form aria-label="고민 쓰기" />,
  PrivacyScope: () => <section aria-label="이 글은 이렇게 보여요" />,
}));

import WritePage from "./page";

describe("고민 쓰기 화면", () => {
  it("016 US1-AC1 입력란 위에 공개 범위 묶음이 있다", () => {
    render(<WritePage />);
    expect(
      screen.getByRole("region", { name: "이 글은 이렇게 보여요" }),
    ).toBeInTheDocument();
  });

  it("016 US1-AC2 공개 범위를 말하는 문장은 묶음 안에만 있다", () => {
    const { container: page } = render(<WritePage />);
    expect(page).not.toHaveTextContent("50자");
    expect(page).not.toHaveTextContent("회원에게만");
  });

  it("016 US1-AC7 머리말에 몬스터, 함께 물리치기, AI 친구 안내가 있다", () => {
    const { container: page } = render(<WritePage />);
    expect(page).toHaveTextContent("털어놓으면 고민이 몬스터가 돼요.");
    expect(page).toHaveTextContent("공감과 댓글로 함께 물리쳐요.");
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
