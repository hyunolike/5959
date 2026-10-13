import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), refresh: vi.fn(), replace: vi.fn() }),
}));

import { EmailLoginDisclosure } from "./email-login-disclosure";

function renderDisclosure() {
  render(
    <QueryClientProvider client={new QueryClient()}>
      <EmailLoginDisclosure next="/write" />
    </QueryClientProvider>,
  );
  return screen.getByRole("button", { name: "이메일로 로그인" });
}

describe("EmailLoginDisclosure", () => {
  it("POLISH-AC19 이메일 입력란은 접힌 채로 시작한다", () => {
    const toggle = renderDisclosure();
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(toggle).toHaveClass("bg-surface-sunken", "min-h-14");
    // 접혀 있는 동안에는 보조기기에도 보이지 않는다.
    expect(screen.queryByLabelText("이메일")).not.toBeVisible();
    expect(
      screen.queryByRole("button", { name: "로그인" }),
    ).not.toBeInTheDocument();
  });

  it("POLISH-AC19 누르면 같은 화면에서 펼쳐지고 초점이 이메일 칸으로 간다", async () => {
    const user = userEvent.setup();
    const toggle = renderDisclosure();

    await user.click(toggle);

    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(document.getElementById(toggle.getAttribute("aria-controls")!)).toBe(
      screen.getByLabelText("이메일").closest("[id]:not(input)"),
    );
    expect(screen.getByLabelText("이메일")).toHaveFocus();
    expect(screen.getByRole("button", { name: "로그인" })).toBeVisible();
  });

  it("POLISH-AC19 다시 접어도 적어 둔 값이 남는다", async () => {
    const user = userEvent.setup();
    const toggle = renderDisclosure();
    await user.click(toggle);
    await user.type(screen.getByLabelText("이메일"), "ogu@example.com");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    await user.click(toggle);

    expect(screen.getByLabelText("이메일")).toHaveValue("ogu@example.com");
  });
});
