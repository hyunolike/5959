import { createHash } from "node:crypto";

import { expect, test, type Page } from "@playwright/test";

import { openEmailLogin } from "./support/login";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다(015 비밀번호 재설정).
// e2e 프로필은 메일을 보내지 않고, 링크의 값이 이메일에서 정해진다(E2eResetTokenGenerator).

const OLD_PASSWORD = "abcd1234";
const NEW_PASSWORD = "newpass5678";

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** e2e 프로필의 링크 값: `e2e-` 뒤에 sha256("reset:" + 이메일)의 16진수. */
function e2eResetToken(email: string): string {
  return `e2e-${createHash("sha256").update(`reset:${email}`).digest("hex")}`;
}

async function signup(page: Page, email: string) {
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(OLD_PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
}

async function loginWith(page: Page, email: string, password: string) {
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
}

test("US1-AC1, US1-AC5, US2-AC1~AC3 로그인 화면에서 재설정을 요청하고 링크로 새 비밀번호를 정해 로그인한다", async ({
  page,
}) => {
  const email = uniqueEmail("reset");
  await signup(page, email);
  await page.context().clearCookies();

  // US1-AC1: 이메일 로그인을 펼치면 재설정으로 가는 길이 있다.
  await page.goto("/login");
  await openEmailLogin(page);
  await page.getByRole("link", { name: "비밀번호를 잊으셨나요?" }).click();
  await page.waitForURL("**/forgot-password");

  // US1-AC5: 가입 여부를 말하지 않는 안내.
  await page.getByLabel("가입한 이메일").fill(email);
  await page.getByRole("button", { name: "재설정 메일 받기" }).click();
  await expect(page.getByRole("status")).toContainText(
    `${email}이 가입한 이메일이라면 재설정 메일이 가요.`,
  );

  // US2-AC1: 메일의 링크로 새 비밀번호를 정하면 로그인 화면으로 간다.
  const resetPath = `/reset-password?token=${e2eResetToken(email)}`;
  await page.goto(resetPath);
  await page.getByLabel("새 비밀번호", { exact: true }).fill(NEW_PASSWORD);
  await page.getByLabel("새 비밀번호 확인").fill(NEW_PASSWORD);
  await page.getByRole("button", { name: "비밀번호 바꾸기" }).click();
  await page.waitForURL("**/login?reset=1");
  await expect(page.getByText("비밀번호를 바꿨어요.")).toBeVisible();

  // US2-AC2: 옛 비밀번호는 안 되고 새 비밀번호로 로그인된다.
  await loginWith(page, email, OLD_PASSWORD);
  await expect(page.locator('form [role="alert"]')).toContainText(
    "이메일 또는 비밀번호가 올바르지 않습니다",
  );
  await loginWith(page, email, NEW_PASSWORD);
  await page.waitForURL("**/onboarding");

  // US2-AC3: 같은 링크는 다시 쓸 수 없다.
  await page.context().clearCookies();
  await page.goto(resetPath);
  await page.getByLabel("새 비밀번호", { exact: true }).fill("another123");
  await page.getByLabel("새 비밀번호 확인").fill("another123");
  await page.getByRole("button", { name: "비밀번호 바꾸기" }).click();
  await expect(page.getByText("쓸 수 없는 링크예요.")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "재설정 메일 다시 받기" }),
  ).toHaveAttribute("href", "/forgot-password");
});

test("US1-AC3 가입하지 않은 이메일도 같은 안내를 받는다", async ({ page }) => {
  const email = uniqueEmail("nobody");
  await page.goto("/forgot-password");
  await page.getByLabel("가입한 이메일").fill(email);
  await page.getByRole("button", { name: "재설정 메일 받기" }).click();
  await expect(page.getByRole("status")).toContainText(
    `${email}이 가입한 이메일이라면 재설정 메일이 가요.`,
  );
});

test("US2-AC9 링크의 값이 없으면 쓸 수 없는 링크라고 알린다", async ({
  page,
}) => {
  await page.goto("/reset-password");
  await expect(page.getByText("쓸 수 없는 링크예요.")).toBeVisible();
});
