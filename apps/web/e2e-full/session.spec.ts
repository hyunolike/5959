import { expect, test, type Page } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API + DB를 상대로 확인한다. 이 API는
// access 토큰 유효 시간이 5초(OGU_AUTH_JWT_ACCESS_TOKEN_TTL=5s)라서, 몇 초만
// 기다리면 BFF의 refresh 재시도를 실제로 거친다.

const PASSWORD = "abcd1234";
const ACCESS_TOKEN_TTL_MS = 5_000;

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

async function createOnboardedMember(page: Page, prefix: string) {
  const email = uniqueEmail(prefix);
  const nickname = uniqueNickname("s");
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  // 홈의 조회(내 프로필, 피드)가 끝나기 전에 쿠키를 지우면 401로 로그인 화면에 튕겨 다음 이동이 끊긴다
  await page.waitForLoadState("networkidle");
  return { email, nickname };
}

async function submitLogin(page: Page, email: string) {
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "로그인" }).click();
}

async function cookieValue(page: Page, name: string) {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === name)?.value;
}

test("US4-AC1 인증 유효 시간이 지나도 보호 기능이 그대로 동작한다", async ({
  page,
}) => {
  const { nickname } = await createOnboardedMember(page, "session-ttl");
  const refreshBefore = await cookieValue(page, "__Host-ogu_rt");

  await page.waitForTimeout(ACCESS_TOKEN_TTL_MS + 1_500);
  await page.goto("/my");

  await expect(page).toHaveURL(/\/my$/);
  await expect(page.getByText(nickname)).toBeVisible();
  // BFF가 만료된 access 토큰 대신 refresh로 새 토큰을 받았다(교체된 refresh 토큰).
  const refreshAfter = await cookieValue(page, "__Host-ogu_rt");
  expect(refreshAfter).toBeDefined();
  expect(refreshAfter).not.toBe(refreshBefore);
});

test("US4-AC4 로그아웃 상태로 /my에 들어가면 로그인 화면으로 이동한다", async ({
  page,
}) => {
  const response = await page.goto("/my");

  await expect(page).toHaveURL(/\/login\?next=%2Fmy$/);
  // 서버의 라우트 가드가 302로 보낸 것이다(마이페이지를 그린 뒤 옮긴 것이 아니다).
  const redirectedFrom = response?.request().redirectedFrom();
  expect(redirectedFrom?.url()).toMatch(/\/my$/);
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await expect(page.getByText("마이페이지")).toHaveCount(0);
});

test("US4-AC5 로그인하면 /my로 돌아온다", async ({ page }) => {
  const { email, nickname } = await createOnboardedMember(page, "session-next");
  await page.context().clearCookies();

  await page.goto("/my");
  await expect(page).toHaveURL(/\/login\?next=%2Fmy$/);
  await submitLogin(page, email);

  await page.waitForURL(/\/my$/);
  await expect(page.getByText(nickname)).toBeVisible();
});

test("US4-AC6 document.cookie에 ogu_ 쿠키가 없다", async ({ page }) => {
  await createOnboardedMember(page, "session-cookie");

  const scriptCookies = await page.evaluate(() => document.cookie);
  expect(scriptCookies).not.toContain("ogu_");
  // 쿠키는 있지만 HttpOnly라 스크립트가 읽지 못한다.
  const cookies = await page.context().cookies();
  const sessionCookies = cookies.filter((cookie) =>
    cookie.name.startsWith("__Host-ogu_"),
  );
  expect(sessionCookies.map((cookie) => cookie.name).sort()).toEqual([
    "__Host-ogu_at",
    "__Host-ogu_ob",
    "__Host-ogu_rt",
  ]);
  for (const cookie of sessionCookies) {
    expect(cookie.httpOnly).toBe(true);
  }
});

for (const next of ["//evil.example", "https://evil.example/home"]) {
  test(`외부 주소 next(${next})는 로그인 뒤 /home으로 간다`, async ({
    page,
  }) => {
    const { email } = await createOnboardedMember(page, "session-evil");
    await page.context().clearCookies();

    await page.goto(`/login?next=${encodeURIComponent(next)}`);
    await submitLogin(page, email);

    await page.waitForURL(/\/home$/);
    expect(new URL(page.url()).origin).toBe("http://localhost:3000");
  });
}
