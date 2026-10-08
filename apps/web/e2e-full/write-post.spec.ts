import { expect, test, type Page } from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. API는 e2e 프로필의
// 가짜 감정 분석기(FakeEmotionAnalyzer, research R3)를 쓰므로 본문 머리말로 결과를 정한다.
// - `[불안:낮음] ...`: 불안, 낮음(최대 HP 10)
// - `[실패:1] ...`: 첫 시도만 실패하고, 재시도(30초 뒤, 10초 주기 스케줄러)에서
//   머리말을 뺀 본문 글자 수로 감정과 강도를 정한다.

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

async function signupAndOnboard(page: Page, prefix: string) {
  await signup(page, uniqueEmail(prefix));
  await page.getByLabel("닉네임").fill(uniqueNickname("w"));
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
}

async function writePost(page: Page, content: string, tone: string) {
  await page.goto("/write");
  await page.getByLabel("고민").fill(content);
  await page.getByRole("button", { name: tone }).click();
  await page.getByRole("button", { name: "올리기" }).click();
}

test("US1-AC1 본문과 말투를 골라 올리면 그 글의 상세 화면으로 이동한다", async ({
  page,
}) => {
  await signupAndOnboard(page, "write-ac1");

  await writePost(
    page,
    "[불안:낮음] 내일 발표가 걱정돼요",
    "무조건 위로해주기",
  );

  await page.waitForURL(/\/post\/\d+$/);
  await expect(
    page.getByText("[불안:낮음] 내일 발표가 걱정돼요"),
  ).toBeVisible();
  await expect(page.getByText("무조건 위로해주기")).toBeVisible();
  // US1-AC4: 분석이 끝나면 불안 몬스터가 HP 가득 찬 상태(10/10)로 나타난다.
  await expect(page.getByText("불안", { exact: true })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText("HP 10/10")).toBeVisible();
});

test("US1-AC3 분석이 끝나지 않았으면 분석 중을 보여 주고, 끝나면 새로고침 없이 몬스터가 나타난다", async ({
  page,
}) => {
  // 첫 분석이 실패해 30초 뒤 재시도로 끝나므로 넉넉히 둔다.
  test.setTimeout(120_000);
  await signupAndOnboard(page, "write-ac3");

  // 머리말을 뺀 "오늘은 아무것도 하기 싫다"는 14자: 감정은 14 % 5 = 짜증, 강도는 낮음(HP 10).
  await writePost(page, "[실패:1] 오늘은 아무것도 하기 싫다", "웃겨주기");
  await page.waitForURL(/\/post\/\d+$/);

  await expect(page.getByText("분석 중")).toBeVisible();

  let reloaded = false;
  page.on("load", () => {
    reloaded = true;
  });

  await expect(page.getByText("짜증", { exact: true })).toBeVisible({
    timeout: 90_000,
  });
  await expect(page.getByText("HP 10/10")).toBeVisible();
  await expect(page.getByText("분석 중")).toHaveCount(0);
  expect(reloaded).toBe(false);
});

test("US1-AC2 500자를 넘거나 비어 있거나 말투를 고르지 않으면 저장하지 않고 안내한다", async ({
  page,
}) => {
  await signupAndOnboard(page, "write-ac2");
  await page.goto("/write");

  await page.getByRole("button", { name: "올리기" }).click();
  await expect(page.getByText("고민을 적어 주세요.")).toBeVisible();
  await expect(page.getByText("댓글 말투를 골라 주세요.")).toBeVisible();

  await page.getByLabel("고민").fill("가".repeat(501));
  await expect(page.getByText("501/500")).toBeVisible();
  await page.getByRole("button", { name: "대신 욕해주기" }).click();
  await page.getByRole("button", { name: "올리기" }).click();

  await expect(page.getByText("500자 이하로 적어 주세요.")).toBeVisible();
  await expect(page).toHaveURL(/\/write$/);
});

test("US1-AC7 온보딩을 마치지 않고 /write에 가면 /onboarding으로 간다", async ({
  page,
}) => {
  await signup(page, uniqueEmail("write-ac7"));

  await page.goto("/write");

  await page.waitForURL("**/onboarding");
  await expect(page.getByRole("heading")).toContainText("프로필을 알려주세요");
});

test("US1-AC7 로그인하지 않고 /write에 가면 로그인 화면으로 간다", async ({
  page,
}) => {
  await page.goto("/write");

  await page.waitForURL(/\/login\?next=%2Fwrite$/);
});
