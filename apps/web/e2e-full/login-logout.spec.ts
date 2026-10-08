import { expect, test, type Page } from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. BFF 라우트나
// API를 목(mock)하지 않는다.

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

const PASSWORD = "abcd1234";

async function signup(page: Page, email: string, password = PASSWORD) {
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
}

async function completeOnboarding(page: Page, nickname: string) {
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
}

/** 가입, 온보딩까지 마친 회원 하나를 만든다. */
async function createOnboardedMember(
  page: Page,
  prefix: string,
): Promise<{ email: string; nickname: string }> {
  const email = uniqueEmail(prefix);
  const nickname = uniqueNickname("n");

  await signup(page, email);
  await completeOnboarding(page, nickname);
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);

  return { email, nickname };
}

async function login(page: Page, email: string, password = PASSWORD) {
  await page.goto("/login");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}

test("US2-AC1 가입과 온보딩을 마친 사용자가 올바른 이메일과 비밀번호로 로그인하면 홈으로 이동한다", async ({
  page,
}) => {
  const { email, nickname } = await createOnboardedMember(page, "login-ok");

  // 세션을 끊고 다시 로그인한다.
  await page.context().clearCookies();

  await login(page, email);

  await page.waitForURL("**/home");
  await expect(page.getByRole("heading")).toContainText(`${nickname}님`);
});

test("US2-AC5 로그아웃하면 로그인 화면으로 이동하고, 뒤로 가기로 /home에 가도 이전 데이터가 보이지 않는다", async ({
  page,
}) => {
  const { email, nickname } = await createOnboardedMember(page, "logout");
  await page.context().clearCookies();
  await login(page, email);
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  await expect(page.getByRole("heading")).toContainText(`${nickname}님`);

  await page.getByRole("button", { name: "로그아웃" }).click();
  await page.waitForURL("**/login");

  // 로그아웃 전 세션으로는 더 이상 보호된 기능을 쓸 수 없다: 뒤로 가기로
  // /home에 가도 이전 사용자의 닉네임이 보이면 안 된다.
  await page.goBack();
  await expect(page.getByText(`${nickname}님`)).not.toBeVisible();
});

test("US2-AC3 한 출처에서 5번 로그인에 실패하면 올바른 비밀번호로도 남은 시간을 분 단위로 안내받는다", async ({
  page,
}) => {
  const { email } = await createOnboardedMember(page, "throttle");
  await page.context().clearCookies();

  // Next.js의 라우트 알림 요소도 role="alert"라 폼의 오류 메시지만 짚는다.
  const formAlert = page.locator('form [role="alert"]');

  await page.goto("/login");
  for (let i = 0; i < 5; i += 1) {
    await page.getByLabel("이메일").fill(email);
    await page.getByLabel("비밀번호").fill("wrongpass123");
    await page.getByRole("button", { name: "로그인" }).click();
    await expect(formAlert).toBeVisible();
  }

  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();

  await expect(formAlert).toContainText("분 후");
  // 여전히 로그인 화면이다(막혔으니 홈으로 가지 않는다).
  await expect(page).toHaveURL(/\/login$/);
});
