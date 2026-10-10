import {
  expect,
  test,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from "@playwright/test";

import { waitForHomeLoaded } from "./support/home";
import { freshBoss } from "./support/raid";

// infra/compose.e2e.yaml로 띄운 실제 API, DB, Redis를 상대로 보스 레이드(006)를 확인한다.
// 보스는 서비스 전체에 하나라 이 파일의 테스트는 차례로 돈다. 테스트마다 HP가 작은 보스를 새로 놓는다.
test.describe.configure({ mode: "serial" });

const PASSWORD = "abcd1234";

interface Member {
  context: BrowserContext;
  page: Page;
}

function uniqueEmail(prefix: string): string {
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}@example.com`;
}

/** 닉네임 규칙(한글/영문/숫자, 1~10자)에 맞는 값만 만든다. */
function uniqueNickname(prefix: string): string {
  return `${prefix}${Math.random().toString(36).slice(2, 6)}`.slice(0, 10);
}

/** 새 컨텍스트에서 가입과 온보딩을 마친 회원. */
async function newMember(browser: Browser, prefix: string): Promise<Member> {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/signup");
  await page.getByLabel("이메일").fill(uniqueEmail(`raid-${prefix}`));
  await page.getByLabel("비밀번호").fill(PASSWORD);
  await page.getByRole("button", { name: "가입하기" }).click();
  await page.waitForURL("**/onboarding");
  await page.getByLabel("닉네임").fill(uniqueNickname(prefix));
  await page.getByLabel("직군").selectOption("DESIGN");
  await page.getByLabel("경력").selectOption("YEAR_3");
  await page.getByRole("button", { name: "완료" }).click();
  await page.waitForURL("**/home");
  await waitForHomeLoaded(page);
  return { context, page };
}

function boss(page: Page): Locator {
  return page.getByRole("region", { name: "보스" });
}

function attackButton(page: Page): Locator {
  return page.getByRole("button", { name: "공격하기" });
}

/** 레이드 화면을 열고 실시간 연결이 레이드 소식을 받을 때까지 기다린다. */
async function openRaid(member: Member, hpText: string) {
  await member.page.goto("/raid");
  await expect(boss(member.page)).toContainText(hpText);
  await expect(
    member.page.getByRole("link", { name: /^알림/ }),
  ).toHaveAttribute("data-stream-status", "open");
}

async function closeAll(...members: Member[]) {
  await Promise.all(members.map((member) => member.context.close()));
}

test("US5-AC7, US1-AC1, US1-AC2 홈의 보스 안내로 레이드에 들어가 공격하면 HP가 줄고 내 기여가 오른다", async ({
  browser,
}) => {
  freshBoss(30);
  const member = await newMember(browser, "a");
  const started = Date.now();

  await member.page.reload();
  await waitForHomeLoaded(member.page);
  await member.page.getByRole("link", { name: /^레이드: 무기력 보스/ }).click();
  await member.page.waitForURL("**/raid");

  await expect(boss(member.page)).toContainText("무기력");
  await expect(boss(member.page)).toContainText("HP 30/30");
  await expect(boss(member.page)).toContainText("함께한 회원0명");
  await expect(boss(member.page)).toContainText("내 기여0");

  await attackButton(member.page).click();

  await expect(boss(member.page)).toContainText("HP 29/30");
  await expect(boss(member.page)).toContainText("함께한 회원1명");
  await expect(boss(member.page)).toContainText("내 기여1");
  // SC-008: 홈에서 첫 공격까지 30초가 걸리지 않는다
  expect(Date.now() - started).toBeLessThan(30_000);

  // US1-AC3: 쿨다운 동안 연달아 눌러도 한 번만 줄어든다
  await expect(attackButton(member.page)).toHaveAttribute(
    "aria-disabled",
    "false",
  );
  await attackButton(member.page).click();
  await attackButton(member.page).click({ force: true });
  await attackButton(member.page).click({ force: true });
  await expect(boss(member.page)).toContainText("HP 28/30");
  await expect(boss(member.page)).toContainText("내 기여2");

  await closeAll(member);
});

test("US2-AC1, US2-AC4 다른 회원의 공격이 새로고침 없이 보인다", async ({
  browser,
}) => {
  freshBoss(30);
  const watcher = await newMember(browser, "w");
  const attacker = await newMember(browser, "x");
  await openRaid(watcher, "HP 30/30");
  await openRaid(attacker, "HP 30/30");

  await attackButton(attacker.page).click();

  await expect(boss(watcher.page)).toContainText("HP 29/30");
  await expect(boss(watcher.page)).toContainText("함께한 회원1명");
  // 보는 사람의 기여는 그대로다
  await expect(boss(watcher.page)).toContainText("내 기여0");

  await closeAll(watcher, attacker);
});

test("US2-AC7, US4-AC1, US4-AC3 보스를 함께 물리치면 모두 결과를 보고 참여한 회원만 알림을 받는다", async ({
  browser,
}) => {
  freshBoss(2);
  const first = await newMember(browser, "f");
  const second = await newMember(browser, "s");
  const bystander = await newMember(browser, "b");
  await openRaid(first, "HP 2/2");
  await openRaid(second, "HP 2/2");
  await openRaid(bystander, "HP 2/2");

  await attackButton(first.page).click();
  await expect(boss(second.page)).toContainText("HP 1/2");
  await attackButton(second.page).click();

  for (const member of [first, second, bystander]) {
    const result = member.page.getByRole("region", { name: "레이드 결과" });
    await expect(result.getByRole("heading")).toHaveText(
      "무기력 보스를 함께 물리쳤어요",
    );
    await expect(result).toContainText("함께한 회원2명");
    await expect(result).toContainText("걸린 시간");
    await expect(result).toContainText("다음 보스는");
    await expect(attackButton(member.page)).toHaveCount(0);
  }
  await expect(
    first.page.getByRole("region", { name: "레이드 결과" }),
  ).toContainText("내 기여1");
  await expect(
    bystander.page.getByRole("region", { name: "레이드 결과" }),
  ).toContainText("내 기여0");

  // 참여한 회원에게만 알림이 온다. 누르면 레이드 화면으로 간다(US4-AC6)
  await first.page.goto("/notifications");
  const item = first.page
    .getByRole("list", { name: "알림" })
    .getByRole("listitem")
    .first();
  await expect(item).toContainText("함께 보스를 물리쳤어요");
  await item.getByRole("link").click();
  await first.page.waitForURL("**/raid");
  await bystander.page.goto("/notifications");
  await expect(bystander.page.getByRole("main")).not.toContainText(
    "함께 보스를 물리쳤어요",
  );

  // US4-AC5: 마이페이지에 함께 물리친 보스 수가 쌓인다
  await first.page.goto("/my");
  await expect(first.page.getByRole("main")).toContainText("함께 물리친 보스1");

  await closeAll(first, second, bystander);
});

test("US1-AC7 로그인하지 않고 레이드에 가면 로그인 화면으로 간다", async ({
  page,
}) => {
  await page.goto("/raid");

  await page.waitForURL(/\/login/);
});
