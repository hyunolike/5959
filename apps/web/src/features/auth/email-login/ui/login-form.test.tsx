import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

import { LoginForm } from "./login-form";

function renderForm() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <LoginForm />
    </QueryClientProvider>,
  );
}

describe("LoginForm 모양", () => {
  it("DS-US2-AC2 입력과 제출 버튼은 56px이다", () => {
    renderForm();
    expect(screen.getByLabelText("이메일")).toHaveClass("h-14");
    expect(screen.getByRole("button", { name: "로그인" })).toHaveClass(
      "min-h-14",
      "bg-action",
    );
  });

  it("DS-US2-AC1 오류가 나면 입력 칸이 잘못됨으로 표시되고 오류 문구와 이어진다", async () => {
    const user = userEvent.setup();
    renderForm();
    await user.click(screen.getByRole("button", { name: "로그인" }));
    const email = screen.getByLabelText("이메일");
    await waitFor(() => expect(email).toHaveAttribute("aria-invalid", "true"));
    const error = document.getElementById("email-error");
    expect(error).toHaveClass("text-label", "text-danger");
    expect(email).toHaveAccessibleDescription(error?.textContent ?? "");
  });
});
