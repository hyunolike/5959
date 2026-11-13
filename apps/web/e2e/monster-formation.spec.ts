import { expect, test } from "@playwright/test";

for (const { width, reducedMotion } of [
  { width: 320, reducedMotion: "no-preference" },
  { width: 390, reducedMotion: "reduce" },
  { width: 1440, reducedMotion: "no-preference" },
] as const) {
  test(`몬스터 대기 중 터치와 키보드 반응, 실제 응답 후 등장 (${width}px, ${reducedMotion})`, async ({
    page,
  }, testInfo) => {
    let ready = false;
    await page.setViewportSize({ width, height: 900 });
    await page.emulateMedia({ reducedMotion });
    await page.setExtraHTTPHeaders({
      cookie: "__Host-ogu_rt=local-review; __Host-ogu_ob=1",
    });
    const author = {
      id: 1,
      nickname: "오구",
      jobRole: "DEVELOPMENT",
      careerYear: "YEAR_1",
      ai: false,
    };
    await page.route("**/api/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      const data =
        path === "/api/posts/7"
          ? {
              postId: 7,
              author,
              content: "내일 발표를 앞두고 조금 긴장돼요.",
              commentTone: "COMFORT_ME",
              mine: true,
              analysisStatus: ready ? "ANALYZED" : "PENDING",
              monster: ready
                ? { emotion: "ANXIETY", hp: 20, maxHp: 20, status: "ALIVE" }
                : null,
              likeCount: 0,
              likedByMe: false,
              commentCount: 0,
              myCommentCounted: false,
              createdAt: "2026-11-13T00:00:00Z",
            }
          : path === "/api/members/me"
            ? { ...author, onboarded: true }
            : path === "/api/posts/7/comments" ||
                path === "/api/posts/7/similar"
              ? { items: [], nextCursor: null }
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
              ? { code: "TEST_UNAVAILABLE", message: "로컬 검증" }
              : null,
        },
      });
    });

    await page.goto("/post/7");
    const stage = page.getByRole("group", { name: "몬스터", exact: true });
    const seed = stage.getByRole("button", {
      name: "마음 덩어리 살짝 건드리기",
    });
    await expect(seed).toBeVisible();
    await expect(stage.getByText("분석 중", { exact: true })).toBeVisible();
    await expect(stage.getByRole("progressbar")).toHaveCount(0);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    const looping = await seed.evaluate(
      (el) =>
        el
          .getAnimations({ subtree: true })
          .filter(
            (animation) =>
              animation.effect?.getTiming().iterations === Infinity,
          ).length,
    );
    expect(looping > 0).toBe(reducedMotion === "no-preference");
    await page.screenshot({
      path: testInfo.outputPath(`formation-${width}.png`),
    });

    await seed.click();
    await expect(stage.getByText("톡! 조금만 기다려 주세요")).toBeVisible();
    await seed.focus();
    await seed.press("Enter");
    await expect(seed).toBeFocused();

    ready = true;
    await expect(stage.getByText("HP 20/20")).toBeVisible({ timeout: 10_000 });
    await expect(seed).toHaveCount(0);
    await expect(stage.getByText("불안 몬스터가 나타났어요.")).toBeVisible();
    await expect
      .poll(() =>
        stage
          .locator("img")
          .evaluate(
            (img: HTMLImageElement) => img.complete && img.naturalWidth > 0,
          ),
      )
      .toBe(true);
    await expect(stage.locator("img").locator("../..")).toHaveCSS(
      "opacity",
      "1",
    );
    await page.screenshot({
      path: testInfo.outputPath(`arrived-${width}.png`),
    });
  });
}
