import { expect, test, type Page } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. BFF 라우트나
// API를 목(mock)하지 않는다.

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

async function signup(page: Page, email: string, password = "abcd1234") {
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

test("US1-AC1, US1-AC4 가입부터 온보딩 완료까지 마치면 홈으로 간다", async ({
  page,
}) => {
  const email = uniqueEmail("signup");
  const nickname = uniqueNickname("n");

  await signup(page, email);
  await expect(page.getByRole("heading")).toContainText("프로필을 알려주세요");

  await completeOnboarding(page, nickname);
  await page.waitForURL("**/home");
  await expect(page.getByRole("heading")).toContainText(`${nickname}님`);
});

test("US1-AC5 이미 쓰는 닉네임이면 완료 전에 중복 안내를 받고 저장되지 않는다", async ({
  page,
}) => {
  const takenNickname = uniqueNickname("dup");

  // 먼저 한 회원이 이 닉네임으로 온보딩을 마친다.
  await signup(page, uniqueEmail("dup-owner"));
  await completeOnboarding(page, takenNickname);
  await page.waitForURL("**/home");

  // 다른 회원이 같은 닉네임으로 온보딩을 시도한다.
  await signup(page, uniqueEmail("dup-challenger"));
  await completeOnboarding(page, takenNickname);

  await expect(page.getByText("이미 사용 중")).toBeVisible();
  // 완료되지 않아 여전히 온보딩 화면이다.
  await expect(page).toHaveURL(/\/onboarding$/);
});

test("US1-AC7 온보딩을 마치지 않고 /home에 가면 /onboarding으로 돌아온다", async ({
  page,
}) => {
  await signup(page, uniqueEmail("not-onboarded"));
  // 아직 온보딩을 마치지 않은 상태에서 보호 화면으로 직접 이동한다.
  await page.goto("/home");

  await page.waitForURL("**/onboarding");
  await expect(page.getByRole("heading")).toContainText("프로필을 알려주세요");
});
