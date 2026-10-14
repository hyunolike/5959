import { expect, test, type Page } from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";
import { openEmailLogin } from "./support/login";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다(013 회원 탈퇴).
// BFF 라우트나 API를 목(mock)하지 않는다.

const PASSWORD = "abcd1234";

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

async function signup(page: Page, email: string) {
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
}

async function onboard(page: Page, nickname: string) {
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
}

async function writePost(page: Page, content: string): Promise<string> {
  await page.goto("/write");
  await page.getByRole("textbox", { name: "고민", exact: true }).fill(content);
  await page.getByRole("button", { name: "무조건 위로해주기" }).click();
  await page.getByRole("button", { name: "올리기" }).click();
  await page.waitForURL(/\/post\/\d+$/);
  return new URL(page.url()).pathname;
}

/** 확인 칸을 채우고 탈퇴한다. 로그인 화면의 안내까지 기다린다. */
async function withdraw(page: Page) {
  await page.getByRole("checkbox", { name: /안내를 모두 읽었고/ }).check();
  await page.getByRole("button", { name: "탈퇴하기" }).click();
  await page.waitForURL("**/login?withdrawn=1");
  await expect(page.getByText(/탈퇴가 끝났어요/)).toBeVisible();
}

test("US1-AC1~AC5, US2-AC2, US4-AC1 탈퇴하면 로그인할 수 없고 글이 사라지며 같은 이메일과 닉네임으로 다시 가입한다", async ({
  page,
  browser,
}) => {
  const email = uniqueEmail("withdraw");
  const nickname = uniqueNickname("wd");
  const content = `[불안:낮음] 탈퇴 전에 쓴 글 ${nickname}`;
  await signup(page, email);
  await onboard(page, nickname);
  const postPath = await writePost(page, content);

  // 다른 회원에게는 그 글이 보인다.
  const otherContext = await browser.newContext();
  const other = await otherContext.newPage();
  await signup(other, uniqueEmail("withdraw-other"));
  await onboard(other, uniqueNickname("ot"));
  await other.goto(postPath);
  await expect(other.getByText(content)).toBeVisible();

  // US1-AC1: 프로필 수정의 맨 아래에서 탈퇴로 간다.
  await page.goto("/my/edit");
  await page.getByRole("link", { name: "회원 탈퇴" }).click();
  await page.waitForURL("**/withdraw");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("회원 탈퇴");
  // US1-AC2, AC3: 안내가 보이고, 확인 칸을 채우기 전에는 눌러도 탈퇴되지 않는다.
  await expect(
    page.getByRole("region", { name: "탈퇴하면 사라져요" }),
  ).toContainText("내가 쓴 글과 댓글");
  await page.getByRole("button", { name: "탈퇴하기" }).click();
  await expect(page).toHaveURL(/\/withdraw$/);

  // US1-AC4
  await withdraw(page);

  // US1-AC5: 같은 계정으로 로그인하면 없는 계정과 같은 안내다.
  await openEmailLogin(page);
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "로그인", exact: true }).click();
  // Next.js의 라우트 알림 요소도 role="alert"라 폼의 오류 메시지만 짚는다.
  await expect(page.locator('form [role="alert"]')).toContainText(
    "이메일 또는 비밀번호가 올바르지 않습니다",
  );
  await expect(page).toHaveURL(/\/login/);

  // US2-AC2: 다른 회원이 그 글의 주소를 열면 지운 글의 안내다.
  await other.goto(postPath);
  await expect(other.getByText(content)).not.toBeVisible();
  await expect(other.getByText("삭제된 글이에요.")).toBeVisible();
  await otherContext.close();

  // US4-AC1, AC3: 같은 이메일로 다시 가입하고 쓰던 닉네임을 다시 쓴다.
  await signup(page, email);
  await onboard(page, nickname);
  await expect(
    page.getByText(`${nickname}님, 반가워요`, { exact: true }),
  ).toBeVisible();
  await page.goto("/my");
  await expect(page.getByText(content)).not.toBeVisible();
});

test("US1-AC7, US1-AC9 온보딩 전에도 탈퇴할 수 있고 취소하면 온보딩으로 돌아온다", async ({
  page,
}) => {
  const email = uniqueEmail("withdraw-early");
  await signup(page, email);

  await page.getByRole("link", { name: "계정 지우기" }).click();
  await page.waitForURL("**/withdraw");
  // US1-AC7: 취소하면 아무것도 바뀌지 않는다.
  await page.getByRole("link", { name: "취소" }).click();
  await page.waitForURL("**/onboarding");

  await page.getByRole("link", { name: "계정 지우기" }).click();
  await page.waitForURL("**/withdraw");
  await withdraw(page);

  // 탈퇴한 뒤에는 보호된 화면이 로그인으로 간다.
  await page.goto("/withdraw");
  await page.waitForURL("**/login");
});

test("US1-AC12 로그인하지 않고 탈퇴 화면을 열면 로그인 화면으로 간다", async ({
  page,
}) => {
  await page.goto("/withdraw");
  await page.waitForURL("**/login");
});
