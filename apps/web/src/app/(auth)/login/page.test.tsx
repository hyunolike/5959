import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";

import LoginPage, { metadata } from "./page";

vi.mock("@/shared/config", () => ({ env: { KAKAO_LOGIN_ENABLED: true } }));
vi.mock("@/features/auth/email-login", () => ({
  EmailLoginDisclosure: ({ next }: { next?: string }) => (
    <button type="button" data-next={next}>
      이메일로 로그인
    </button>
  ),
}));

async function renderPage(params: Record<string, string> = { next: "/write" }) {
  return render(await LoginPage({ searchParams: Promise.resolve(params) }));
}

it("GUEST-US1-AC1 로그인 화면에서 내부 홈을 먼저 둘러볼 수 있다", async () => {
  await renderPage();
  expect(screen.getByRole("link", { name: "먼저 둘러보기" })).toHaveAttribute(
    "href",
    "/home",
  );
});

it("POLISH-AC20 첫 화면은 오구와 서비스 소개로 시작하고 화면의 제목은 로그인이다", async () => {
  const { container } = await renderPage();
  // 문서 제목과 같은 h1을 읽히기만 하게 두고, 보이는 머리 문구는 따로 둔다.
  expect(screen.getByRole("heading", { level: 1, name: "로그인" })).toHaveClass(
    "sr-only",
  );
  expect(metadata.title).toBe("로그인");
  expect(screen.getByText("오늘 마음은 어때요?")).toHaveClass("font-extrabold");
  expect(container).toHaveTextContent("고민을 털어놓으면 감정 몬스터가 돼요.");
  expect(container).toHaveTextContent("공감과 댓글로 함께 물리쳐요.");
  // 오구 그림은 장식이다.
  expect(container.querySelector('img[src*="ogu-hello"]')).toHaveAttribute(
    "alt",
    "",
  );
  const emotions = screen.getByRole("list", { name: "감정 몬스터의 종류" });
  expect(emotions).toHaveTextContent("불안");
  expect(emotions.querySelectorAll("li")).toHaveLength(5);
});

it("POLISH-AC20 카카오, 구글, 이메일 순으로 놓이고 가려던 곳을 넘긴다", async () => {
  await renderPage();
  const kakao = screen.getByRole("link", { name: "카카오로 계속하기" });
  const google = screen.getByRole("link", { name: "구글로 계속하기" });
  const email = screen.getByRole("button", { name: "이메일로 로그인" });
  expect(google).toHaveAttribute(
    "href",
    "/api/auth/oauth/google?next=%2Fwrite",
  );
  expect(email).toHaveAttribute("data-next", "/write");
  expect(
    kakao.compareDocumentPosition(google) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(
    google.compareDocumentPosition(email) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
  expect(screen.getByRole("link", { name: "회원가입" })).toHaveAttribute(
    "href",
    "/signup?next=%2Fwrite",
  );
});

it("US3-AC4 외부 계정 로그인이 실패해 돌아오면 버튼 위에서 알린다", async () => {
  await renderPage({ error: "oauth_failed" });
  const alert = screen.getByRole("alert");
  expect(alert).toHaveTextContent("외부 계정으로 로그인하지 못했습니다");
  expect(
    alert.compareDocumentPosition(
      screen.getByRole("link", { name: "구글로 계속하기" }),
    ) & Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy();
});
