import { expect, test, type Page } from "@playwright/test";

async function sleepingApi(page: Page) {
  const state = { up: false, requests: [] as string[] };
  await page.setExtraHTTPHeaders({
    cookie: "__Host-ogu_rt=recovery-test; __Host-ogu_ob=1",
  });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/health/live") {
      await route.fulfill({ json: { status: state.up ? "UP" : "DOWN" } });
      return;
    }
    state.requests.push(path);
    const data =
      path === "/api/members/me"
        ? {
            id: 1,
            nickname: "돌아온 회원",
            jobRole: "DEVELOPMENT",
            careerYear: "YEAR_1",
            onboarded: true,
          }
        : path === "/api/feed" || path === "/api/members/me/posts"
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
          data === null
            ? { code: "TEST_UNAVAILABLE", message: "검증용 응답" }
            : null,
      },
    });
  });
  return state;
}

for (const width of [320, 1440]) {
  test(`로그인 유지 상태에서 5분 넘게 잠든 API를 기다리고 새로고침 없이 복구한다 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 900 });
    await page.clock.install();
    const api = await sleepingApi(page);
    await page.goto("/home");
    await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
    await page.clock.fastForward("05:30");
    await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
    expect(api.requests).toEqual([]);
    await page.screenshot({ path: testInfo.outputPath(`waking-${width}.png`) });
    api.up = true;
    await page.clock.runFor(5_100);
    await expect(page.getByText("돌아온 회원님, 반가워요")).toBeVisible();
    await expect(page.getByText("아직 올라온 고민이 없어요.")).toBeVisible();
    expect(api.requests).toContain("/api/feed");
    await expect(page.getByText("서버를 깨우고 있어요")).toHaveCount(0);
  });
}

test("7분 뒤 대기를 끝내고 다시 연결하기로 자동 복구한다", async ({ page }) => {
  await page.clock.install();
  const api = await sleepingApi(page);
  await page.goto("/home");
  await expect(page.getByText("서버를 깨우고 있어요")).toBeVisible();
  await page.clock.fastForward("07:01");
  await expect(page.getByText("서버가 아직 일어나지 않았어요")).toBeVisible();
  await expect(page.getByLabel("깨우는 중")).toHaveCount(0);
  expect(api.requests).toEqual([]);
  api.up = true;
  await page.getByRole("button", { name: "다시 연결하기" }).click();
  await expect(page.getByText("돌아온 회원님, 반가워요")).toBeVisible();
});
