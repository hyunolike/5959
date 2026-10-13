import type { Page } from "@playwright/test";

/**
 * 로그인 첫 화면의 이메일 입력란은 접혀 있다. 접혀 있을 때만 펼친다(다시 누르면 접힌다).
 * 로그인 버튼은 이름이 "이메일로 로그인"과 겹치므로 누를 때는 `exact: true`로 고른다.
 */
export async function openEmailLogin(page: Page) {
  const toggle = page.getByRole("button", { name: "이메일로 로그인" });
  if ((await toggle.getAttribute("aria-expanded")) === "false") {
    await toggle.click();
  }
}
