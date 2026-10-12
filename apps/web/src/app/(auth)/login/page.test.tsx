import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";

import LoginPage from "./page";

vi.mock("@/shared/config", () => ({ env: { KAKAO_LOGIN_ENABLED: false } }));
vi.mock("@/features/auth/email-login", () => ({
  LoginForm: () => <form aria-label="이메일 로그인" />,
}));

it("GUEST-US1-AC1 로그인 화면에서 내부 홈을 먼저 둘러볼 수 있다", async () => {
  render(
    await LoginPage({ searchParams: Promise.resolve({ next: "/write" }) }),
  );
  expect(screen.getByRole("link", { name: "먼저 둘러보기" })).toHaveAttribute(
    "href",
    "/home",
  );
  expect(
    screen.getByRole("form", { name: "이메일 로그인" }),
  ).toBeInTheDocument();
});
