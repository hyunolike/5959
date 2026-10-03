import { expect, test, type Page } from "@playwright/test";

// infra/compose.e2e.yaml로 띄운 실제 API(e2e 프로필, 가짜 OAuth 제공자) + DB를
// 상대로 확인한다. 웹도 APP_ENV=e2e라서 OAuth 시작 라우트가 제공자 대신 자기
// 콜백으로 바로 보낸다. 쿼리 e2e_id, e2e_email, e2e_outcome으로 제공자가 돌려줄
// 결과를 고른다(apps/web/src/app/api/auth/oauth/[provider]/route.ts).

function uniqueId(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

function startUrl(
  provider: "kakao" | "google",
  query: Record<string, string>,
): string {
  return `/api/auth/oauth/${provider}?${new URLSearchParams(query).toString()}`;
}

async function completeOnboarding(page: Page, nickname: string) {
  await page.getByLabel("닉네임").fill(nickname);
  await page.getByLabel("직군").selectOption("DEVELOPMENT");
  await page.getByLabel("경력").selectOption("YEAR_1");
  await page.getByRole("button", { name: "완료" }).click();
}

test("US3-AC1 처음 쓰는 카카오 계정으로 로그인하면 새 회원이 만들어지고 온보딩 화면으로 이동한다", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("link", { name: "카카오로 계속하기" }).click();

  await page.waitForURL("**/onboarding");
  const nickname = uniqueNickname("k");
  await completeOnboarding(page, nickname);
  await page.waitForURL("**/home");
  // 홈의 조회(내 프로필, 피드)가 끝나기 전에 쿠키를 지우면 401로 로그인 화면에 튕겨 다음 이동이 끊긴다
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("heading")).toContainText(`${nickname}님`);
});

test("US3-AC2 온보딩까지 마친 구글 계정으로 다시 로그인하면 온보딩 없이 홈으로 이동한다", async ({
  page,
}) => {
  const query = {
    next: "/home",
    e2e_id: uniqueId("google"),
    e2e_email: `${uniqueId("g")}@example.com`,
  };

  await page.goto(startUrl("google", query));
  await page.waitForURL("**/onboarding");
  const nickname = uniqueNickname("g");
  await completeOnboarding(page, nickname);
  await page.waitForURL("**/home");
  // 홈의 조회(내 프로필, 피드)가 끝나기 전에 쿠키를 지우면 401로 로그인 화면에 튕겨 다음 이동이 끊긴다
  await page.waitForLoadState("networkidle");

  await page.context().clearCookies();

  await page.goto(startUrl("google", query));
  await page.waitForURL("**/home");
  // 홈의 조회(내 프로필, 피드)가 끝나기 전에 쿠키를 지우면 401로 로그인 화면에 튕겨 다음 이동이 끊긴다
  await page.waitForLoadState("networkidle");
  await expect(page.getByRole("heading")).toContainText(`${nickname}님`);
});

test("US3-AC3 이메일로 가입한 주소의 외부 계정으로 로그인하면 합치지 않고 이메일 로그인을 안내한다", async ({
  page,
}) => {
  const email = `${uniqueId("dup")}@example.com`;
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill("abcd1234");
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.context().clearCookies();

  await page.goto(
    startUrl("kakao", { e2e_id: uniqueId("kakao"), e2e_email: email }),
  );

  await page.waitForURL("**/login?error=email_registered");
  await expect(
    page
      .getByRole("alert")
      .filter({ hasText: "이메일과 비밀번호로 로그인해주세요" }),
  ).toBeVisible();
});

test("US3-AC4 외부 로그인에서 동의를 취소하면 로그인 화면으로 돌아와 오류 화면 없이 다시 시도할 수 있다", async ({
  page,
}) => {
  await page.goto("/login");
  await page.goto(startUrl("kakao", { e2e_outcome: "cancel" }));

  await page.waitForURL("**/login?error=oauth_cancelled");
  await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "외부 계정 로그인을 취소했습니다" }),
  ).toBeVisible();
  await expect(
    page.locator('main [role="alert"], form [role="alert"]'),
  ).toHaveCount(0);

  // 같은 화면에서 다시 시도하면 로그인이 이어진다.
  await page.getByRole("link", { name: "카카오로 계속하기" }).click();
  await page.waitForURL("**/onboarding");
});
