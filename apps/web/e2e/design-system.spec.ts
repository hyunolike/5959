import { expect, test } from "@playwright/test";

test("US3-AC4 회원가입과 로그인 사이에서 원래 목적지를 유지한다", async ({
  page,
}) => {
  await page.goto("/login?next=%2Fwrite");
  await page.getByRole("link", { name: "회원가입", exact: true }).click();
  await expect(page).toHaveURL(/\/signup\?next=%2Fwrite$/);
  await page.getByRole("link", { name: "로그인", exact: true }).click();
  await expect(page).toHaveURL(/\/login\?next=%2Fwrite$/);
});

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`US1-AC1, US3-AC4 로그인 안내가 작은 화면과 다크 모드에서도 읽힌다 (${viewport.width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize(viewport);
    await page.emulateMedia({ colorScheme: "dark", reducedMotion: "reduce" });
    await page.goto("/login?error=oauth_failed&next=%2Fwrite");
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      "외부 계정으로 로그인하지 못했습니다",
    );
    await expect(
      page.getByRole("link", { name: "구글로 계속하기" }),
    ).toHaveAttribute("href", /next=%2Fwrite$/);
    await page.getByLabel("이메일", { exact: true }).focus();
    await expect(page.getByLabel("이메일", { exact: true })).toBeFocused();
    await page.evaluate(() => document.fonts.ready);
    const layout = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth,
      background: getComputedStyle(document.body).backgroundColor,
      font: getComputedStyle(document.body).fontFamily,
    }));
    expect(layout.overflow).toBe(false);
    expect(layout.background).toBe("rgb(255, 255, 255)");
    expect(layout.font).toContain("oguSans");
    if (viewport.width < 768) {
      const google = await page
        .getByRole("link", { name: "구글로 계속하기" })
        .boundingBox();
      const signup = await page
        .getByRole("link", { name: "회원가입", exact: true })
        .boundingBox();
      expect(google!.y).toBeLessThan(360);
      expect(signup!.y + signup!.height).toBeLessThan(viewport.height);
    }
    await page.screenshot({
      path: testInfo.outputPath("login.png"),
      fullPage: viewport.width >= 768,
    });
  });
  test(`US2-AC1, US1-AC1 탐색을 반복하고 실패 후 다시 작성할 수 있다 (${viewport.width}px)`, async ({
    page,
  }, testInfo) => {
    let feedUnavailable = false;
    let postRequests = 0;
    let releasePost!: () => void;
    const pendingPost = new Promise<void>((resolve) => {
      releasePost = resolve;
    });
    await page.setViewportSize(viewport);
    await page.setExtraHTTPHeaders({
      cookie: "__Host-ogu_rt=design-test; __Host-ogu_ob=1",
    });
    await page.route("**/api/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path === "/api/posts" && route.request().method() === "POST") {
        postRequests += 1;
        if (postRequests === 1) {
          await pendingPost;
          await route.fulfill({
            status: 503,
            json: {
              success: false,
              data: null,
              error: {
                code: "TEST_UNAVAILABLE",
                message: "잠시 후 다시 시도해주세요.",
              },
            },
          });
        } else {
          await route.fulfill({
            status: 201,
            json: { success: true, data: { postId: 42 }, error: null },
          });
        }
        return;
      }
      const data =
        path === "/api/members/me"
          ? {
              id: 1,
              nickname: "오구",
              jobRole: "DEVELOPMENT",
              careerYear: "YEAR_3",
              onboarded: true,
            }
          : path === "/api/feed" && !feedUnavailable
            ? { items: [], nextCursor: null }
            : path === "/api/raid"
              ? { boss: null, available: true, myDamage: 0, nextBossAt: null }
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
                  message: "테스트에서 실시간 연결을 사용하지 않습니다.",
                },
              }
            : { success: true, data, error: null },
      });
    });
    await page.goto("/home");
    await expect(page.getByRole("heading", { level: 1 })).toHaveText(
      "마음 이야기",
    );
    const nav = page.getByRole("navigation", {
      name: viewport.width < 768 ? "모바일 주요 메뉴" : "주요 메뉴",
      exact: true,
    });
    await expect(
      nav.getByRole("link", { name: "마음 이야기" }),
    ).toHaveAttribute("aria-current", "page");
    await page.screenshot({
      path: testInfo.outputPath("home.png"),
      fullPage: true,
    });
    await page.getByRole("button", { name: "인기순" }).click();
    await expect(page).toHaveURL(/order=POPULAR/);
    await expect(page.getByRole("button", { name: "인기순" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await page.getByText("필터", { exact: true }).click();
    await page.getByRole("button", { name: "개발", exact: true }).click();
    await expect(page).toHaveURL(/jobRole=DEVELOPMENT/);
    await page.getByRole("button", { name: "필터 지우기" }).click();
    await expect(page).toHaveURL(/\/home\?order=POPULAR$/);
    await page.reload();
    await expect(page.getByRole("button", { name: "인기순" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await nav.getByRole("link", { name: "고민 쓰기" }).click();
    await expect(
      page.getByRole("heading", { name: "고민 쓰기", exact: true }),
    ).toBeVisible();
    await page
      .getByLabel("고민", { exact: true })
      .fill("마음을 나누고 싶어요.");
    await page.getByRole("button", { name: "따뜻한 조언해주기" }).click();
    await expect(
      page.getByRole("button", { name: "따뜻한 조언해주기" }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(nav.getByRole("link", { name: "고민 쓰기" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    const overflow = await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth,
    );
    expect(overflow).toBe(false);
    await page.goBack();
    await expect(page).toHaveURL(/\/home\?order=POPULAR$/);
    await expect(page.getByRole("button", { name: "인기순" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await nav.getByRole("link", { name: "고민 쓰기" }).click();
    await page.getByRole("button", { name: "올리기", exact: true }).click();
    await expect(page.getByRole("main").getByRole("alert")).not.toHaveCount(0);
    expect(postRequests).toBe(0);
    const content = page.getByLabel("고민", { exact: true });
    await content.fill("마음을 나누고 싶어요.");
    await page.getByRole("button", { name: "따뜻한 조언해주기" }).click();
    const submit = page.getByRole("button", { name: "올리기", exact: true });
    await submit.click();
    await expect(submit).toBeDisabled();
    await submit.click({ force: true });
    await expect.poll(() => postRequests).toBe(1);
    releasePost();
    await expect(page.getByRole("main").getByRole("alert")).toHaveText(
      "글을 올리지 못했습니다. 잠시 후 다시 시도해주세요.",
    );
    await expect(content).toHaveValue("마음을 나누고 싶어요.");
    await expect(
      page.getByRole("button", { name: "따뜻한 조언해주기" }),
    ).toHaveAttribute("aria-pressed", "true");
    await expect(submit).toBeEnabled();
    await page.screenshot({
      path: testInfo.outputPath("write-failure.png"),
      fullPage: true,
    });
    await submit.click();
    await expect(page).toHaveURL(/\/post\/42$/);
    expect(postRequests).toBe(2);
    feedUnavailable = true;
    await nav.getByRole("link", { name: "마음 이야기" }).click();
    await expect(page.getByRole("main").getByRole("alert")).toContainText(
      "피드를 불러오지 못했습니다",
    );
    feedUnavailable = false;
    await page.reload();
    await expect(page.getByText("아직 올라온 고민이 없어요.")).toBeVisible();
  });
}
