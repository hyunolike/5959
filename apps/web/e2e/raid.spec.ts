import { expect, test } from "@playwright/test";

const member = {
  id: 2,
  nickname: "로컬 검증회원",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
  onboarded: true,
};

function raidState(hp: number) {
  return {
    boss: {
      bossId: 7,
      emotion: "IRRITATION",
      maxHp: 10000,
      hp,
      status: "ALIVE",
      participantCount: 213,
      spawnedAt: "2026-10-12T00:00:00Z",
      endedAt: null,
    },
    myDamage: 14,
    nextBossAt: null,
    available: true,
    epoch: 10,
  };
}

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
  { width: 320, height: 844 },
]) {
  test(`DS-US3-AC3, DS-US3-AC6 레이드 무대가 보스의 감정 색이고 큰 HP 숫자가 넘치지 않는다 (${viewport.width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.setExtraHTTPHeaders({
      cookie: "__Host-ogu_rt=local-review; __Host-ogu_ob=1",
    });
    await page.route("**/api/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const data =
        path === "/api/members/me"
          ? member
          : path === "/api/raid"
            ? raidState(10000)
            : path === "/api/notifications/unread-count"
              ? { count: 0 }
              : undefined;
      await route.fulfill({
        status: data === undefined ? 503 : 200,
        json:
          data === undefined
            ? {
                success: false,
                data: null,
                error: {
                  code: "TEST_UNAVAILABLE",
                  message: "실시간 연결은 로컬 검증에서 사용하지 않습니다.",
                },
              }
            : { success: true, data, error: null },
      });
    });

    await page.goto("/raid");
    const stage = page.getByRole("region", { name: "보스" });
    // 가장 긴 숫자(최대 HP 그대로)에서도 넘치지 않아야 한다.
    await expect(stage).toContainText("HP 10,000/10,000");
    await expect(stage).toContainText("짜증");
    await expect(stage).toContainText("함께한 회원213명");
    await expect(stage).toContainText("내 기여14");
    await expect(stage).toHaveCSS("background-color", "rgb(253, 234, 234)");
    await expect(stage.getByText("10,000", { exact: true })).toHaveCSS(
      "font-size",
      "36px",
    );
    await expect(
      stage.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveCSS("height", "22px");
    await expect(page.getByRole("button", { name: "공격하기" })).toHaveCSS(
      "background-color",
      "rgb(255, 214, 10)",
    );

    const bossImage = stage.getByRole("img", { name: /보스, / });
    await expect
      .poll(() =>
        bossImage.evaluate(
          (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
        ),
      )
      .toBe(true);
    // 좁은 화면에서도 보스 그림이 무대의 안쪽 여백(20px)을 넘지 않는다.
    const stageBox = (await stage.boundingBox())!;
    const imageBox = (await bossImage.boundingBox())!;
    expect(imageBox.x).toBeGreaterThanOrEqual(stageBox.x + 19.5);
    expect(imageBox.x + imageBox.width).toBeLessThanOrEqual(
      stageBox.x + stageBox.width - 19.5,
    );
    await page.evaluate(() => document.fonts.ready);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: testInfo.outputPath("raid.png"),
      fullPage: true,
    });
  });
}
