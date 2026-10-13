import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

import { SignupForm } from "./signup-form";

function renderForm() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SignupForm />
    </QueryClientProvider>,
  );
}

describe("SignupForm 모양", () => {
  it("DS-US2-AC2 입력과 제출 버튼은 52px이다", () => {
    renderForm();
    expect(screen.getByLabelText("이메일")).toHaveClass("h-13");
    expect(screen.getByRole("button", { name: "가입하기" })).toHaveClass(
      "min-h-13",
      "bg-action",
    );
  });

  it("DS-US3-AC4 폼에 감정 색과 게임 요소가 없다", () => {
    const { container } = render(
      <QueryClientProvider client={new QueryClient()}>
        <SignupForm />
      </QueryClientProvider>,
    );
    expect(
      container.querySelector(
        '[data-emotion], [class*="emotion"], [class*="bg-game"]',
      ),
    ).toBeNull();
  });
});
