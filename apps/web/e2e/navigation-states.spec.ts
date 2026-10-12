import { expect, test } from "@playwright/test";

const member = {
  id: 1,
  nickname: "로컬 검증회원",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
  onboarded: true,
};
test.beforeEach(async ({ page }) => {
  await page.setExtraHTTPHeaders({
    cookie: "__Host-ogu_rt=local-review; __Host-ogu_ob=1",
  });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data =
      path === "/api/members/me"
        ? member
        : path === "/api/feed"
          ? { items: [], nextCursor: null }
          : path === "/api/raid"
            ? { boss: null, available: true, myDamage: 0, nextBossAt: null }
            : path === "/api/notifications/unread-count"
              ? { count: 0 }
              : null;
    await route.fulfill({
      status: data === null ? 503 : 200,
      json: {
        success: data !== null,
        data,
        error:
          data === null ? { code: "LOCAL_TEST", message: "로컬 검증" } : null,
      },
    });
  });
});

for (const width of [320, 390, 430, 1440]) {
  test(`실제 경로·키보드·뒤로 가기·터치 크기 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    await page.goto("/home");
    const nav = page.getByRole("navigation", {
      name: width < 768 ? "모바일 주요 메뉴" : "주요 메뉴",
    });
    const home = nav.getByRole("link", { name: "마음 이야기", exact: true });
    const write = nav.getByRole("link", { name: "고민 쓰기", exact: true });
    await expect(home).toHaveAttribute("aria-current", "page");
    await home.click();
    await expect(nav.locator('[data-navigation-pending="true"]')).toHaveCount(
      0,
    );
    await write.focus();
    await write.press("Enter");
    await expect(page).toHaveURL(/\/write$/);
    await expect(write).toHaveAttribute("aria-current", "page");
    await expect(home).not.toHaveAttribute("aria-current");
    const sizes = await nav.getByRole("link").evaluateAll((nodes) =>
      nodes.map((el) => ({
        width: el.getBoundingClientRect().width,
        height: el.getBoundingClientRect().height,
      })),
    );
    expect(sizes.every((s) => s.width >= 44 && s.height >= 44)).toBe(true);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: testInfo.outputPath(`write-${width}.png`),
      fullPage: false,
    });
    await page.goBack();
    await expect(home).toHaveAttribute("aria-current", "page");
    await page.goForward();
    await expect(write).toHaveAttribute("aria-current", "page");
  });
}

test("실제 RSC 대기와 현재 선택을 분리하고 연속 클릭의 마지막 목적지만 대기 표시", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let release!: () => void;
  const gate = new Promise<void>((r) => (release = r));
  await page.route(/\/(write|raid)\?.*_rsc=/, async (route) => {
    await gate;
    await route.continue();
  });
  await page.goto("/home");
  const nav = page.getByRole("navigation", { name: "모바일 주요 메뉴" });
  const home = nav.getByRole("link", { name: "마음 이야기", exact: true });
  const write = nav.getByRole("link", { name: "고민 쓰기", exact: true });
  const raid = nav.getByRole("link", { name: "함께 이겨내기", exact: true });
  await write.click();
  await expect(write.locator('[data-navigation-pending="true"]')).toHaveCount(
    1,
  );
  await expect(home).toHaveAttribute("aria-current", "page");
  await expect(write).not.toHaveAttribute("aria-current");
  await raid.click();
  await expect(raid.locator('[data-navigation-pending="true"]')).toHaveCount(1);
  await expect(write.locator('[data-navigation-pending="true"]')).toHaveCount(
    0,
  );
  release();
  await expect(page).toHaveURL(/\/raid$/);
  await expect(raid).toHaveAttribute("aria-current", "page");
  await expect(nav.locator('[data-navigation-pending="true"]')).toHaveCount(0);
});

test("320px 감정 통계의 낭독용 표가 페이지 가로 스크롤을 만들지 않는다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 844 });
  const emotions = [
    "ANXIETY",
    "LETHARGY",
    "LONELINESS",
    "SELF_DEPRECATION",
    "IRRITATION",
  ];
  await page.route("**/api/members/me/emotion-stats", (route) =>
    route.fulfill({
      json: {
        success: true,
        error: null,
        data: {
          totalMonsters: 4,
          defeatedMonsters: 2,
          defeatedTogether: 1,
          raidBossesDefeated: 0,
          topEmotion: "ANXIETY",
          distribution: emotions.map((emotion, i) => ({
            emotion,
            count: i === 0 ? 4 : 0,
            percent: i === 0 ? 100 : 0,
          })),
          weekly: [
            {
              weekStart: "2026-10-05",
              counts: emotions.map((emotion, i) => ({
                emotion,
                count: i === 0 ? 4 : 0,
              })),
            },
          ],
        },
      },
    }),
  );
  await page.goto("/my");
  await expect(
    page.getByRole("table", { name: "최근 8주 감정별 몬스터 수" }),
  ).toBeAttached();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});
