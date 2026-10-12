import { expect, test } from "@playwright/test";
import { waitForHomeLoaded } from "../e2e-full/support/home";

const author = {
  id: 2,
  nickname: "로컬 검증회원",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
};
const item = {
  postId: 7,
  author,
  contentPreview: "로컬 검증용 글입니다. 내일 발표를 앞두고 조금 긴장돼요.",
  analysisStatus: "ANALYZED",
  monster: { emotion: "ANXIETY", hp: 7, maxHp: 10, status: "ALIVE" },
  likeCount: 3,
  likedByMe: true,
  commentCount: 2,
  hidden: false,
  createdAt: "2026-10-12T08:30:00Z",
};

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
]) {
  test(`본문과 실제 상태 정보를 읽고 상세에서 피드로 돌아온다 (${viewport.width}px)`, async ({
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
          ? { ...author, onboarded: true }
          : path === "/api/feed"
            ? {
                items: [
                  item,
                  {
                    ...item,
                    postId: 8,
                    contentPreview: "로컬 검증용 분석 중인 글입니다.",
                    monster: null,
                    analysisStatus: "PENDING",
                    likedByMe: false,
                  },
                ],
                nextCursor: null,
              }
            : path === "/api/posts/7"
              ? {
                  ...item,
                  content: item.contentPreview,
                  mine: false,
                  commentTone: "WARM_ADVICE",
                }
              : path === "/api/posts/7/comments" ||
                  path === "/api/posts/7/similar"
                ? { items: [], nextCursor: null }
                : path === "/api/raid"
                  ? {
                      boss: null,
                      available: true,
                      myDamage: 0,
                      nextBossAt: null,
                    }
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
    await page.goto("/home");
    await waitForHomeLoaded(page);
    await expect(page.getByText(item.contentPreview)).toBeVisible();
    await expect(page.getByText("분석 중", { exact: true })).toBeVisible();
    await expect(
      page.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "7");
    const post = page
      .getByRole("region", { name: "피드", exact: true })
      .getByRole("link")
      .filter({ hasText: item.contentPreview });
    await expect(post.locator("time")).toHaveAttribute(
      "datetime",
      item.createdAt,
    );
    await expect(post.getByText("공감 3", { exact: true })).toBeVisible();
    await expect(post.getByText("댓글 2", { exact: true })).toBeVisible();
    expect(await post.getByRole("button").count()).toBe(0);
    await page.evaluate(() => document.fonts.ready);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: testInfo.outputPath("feed-populated.png"),
      fullPage: true,
    });
    for (let i = 0; i < 2; i += 1) {
      await page.getByRole("button", { name: "인기순", exact: true }).click();
      await expect(page).toHaveURL(/order=POPULAR/);
      await post.click();
      await expect(page).toHaveURL(/\/post\/7$/);
      await expect(
        page.getByRole("article", { name: "고민 글" }),
      ).toBeVisible();
      await page.goBack();
      await expect(page).toHaveURL(/\/home\?order=POPULAR$/);
      await expect(
        page.getByRole("button", { name: "인기순", exact: true }),
      ).toHaveAttribute("aria-pressed", "true");
    }
    await page.goto("/home");
    await page.keyboard.press("Tab");
    await expect(
      page.getByRole("link", { name: "본문으로 건너뛰기" }),
    ).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("main")).toBeFocused();
  });
}
