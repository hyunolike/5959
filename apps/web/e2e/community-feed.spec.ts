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
  { width: 320, height: 844 },
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
                  {
                    ...item,
                    postId: 9,
                    author: {
                      ...author,
                      nickname: "아주긴닉네임을쓰는검증회원",
                    },
                    contentPreview: "로컬 검증용 처치된 몬스터 글입니다.",
                    monster: {
                      emotion: "SELF_DEPRECATION",
                      hp: 0,
                      maxHp: 10,
                      status: "DEFEATED",
                    },
                    likeCount: 12,
                    likedByMe: false,
                    commentCount: 34,
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
    const post = page
      .getByRole("region", { name: "피드", exact: true })
      .getByRole("link")
      .filter({ hasText: item.contentPreview });
    await expect(
      post.getByRole("progressbar", { name: "몬스터 HP" }),
    ).toHaveAttribute("aria-valuenow", "7");
    // 좁은 화면에서도 처치된 몬스터의 HP 글자와 처치됨이 글자 단위로 꺾이지 않는다(한 줄).
    const defeated = page
      .getByRole("region", { name: "피드", exact: true })
      .getByRole("link")
      .filter({ hasText: "로컬 검증용 처치된 몬스터 글입니다." });
    for (const text of ["HP 0/10", "처치됨"]) {
      const box = await defeated.getByText(text, { exact: true }).boundingBox();
      expect(box!.height).toBeLessThan(24);
    }
    await expect(post.locator("time")).toHaveAttribute(
      "datetime",
      item.createdAt,
    );
    await expect(post.getByText("공감 3", { exact: true })).toBeVisible();
    await expect(post.getByText("댓글 2", { exact: true })).toBeVisible();
    // DS-US3-AC1: 작성 시각은 상대 시각이거나 날짜다. 피드의 몬스터는 48px 자리다.
    await expect(post.locator("time")).toHaveText(
      /^(방금|\d+분 전|\d+시간 전|\d+일 전|\d{4}\.\d{2}\.\d{2})$/,
    );
    await expect(post.getByText("불안", { exact: true })).toBeVisible();
    const monsterImage = page
      .getByRole("region", { name: "피드", exact: true })
      .getByRole("img", { name: /몬스터, / })
      .first();
    await expect
      .poll(() =>
        monsterImage.evaluate(
          (image: HTMLImageElement) => image.complete && image.naturalWidth > 0,
        ),
      )
      .toBe(true);
    const thumb = await monsterImage.boundingBox();
    expect(thumb!.width).toBeGreaterThanOrEqual(44);
    expect(thumb!.width).toBeLessThanOrEqual(48);
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
    // POLISH-AC14: 정렬과 필터 여닫이는 한 줄에 있고 가장 좁은 화면에서도 겹치지 않는다.
    const sortBox = (await page
      .getByRole("group", { name: "정렬" })
      .boundingBox())!;
    const filterBox = (await page
      .locator("summary", { hasText: "필터" })
      .boundingBox())!;
    expect(Math.abs(sortBox.y - filterBox.y)).toBeLessThanOrEqual(1);
    expect(sortBox.x + sortBox.width).toBeLessThanOrEqual(filterBox.x - 8);
    // 고른 필터의 개수가 붙어 여닫이가 넓어져도 겹치지 않고, 펼친 칩은 그 아래에 놓인다.
    await page.goto("/home?jobRole=DEVELOPMENT&jobRole=DESIGN");
    const counted = page.locator("summary", { hasText: "필터 (2)" });
    await expect(counted).toBeVisible();
    const countedBox = (await counted.boundingBox())!;
    const sortAgain = (await page
      .getByRole("group", { name: "정렬" })
      .boundingBox())!;
    expect(sortAgain.x + sortAgain.width).toBeLessThanOrEqual(countedBox.x - 4);
    const chips = (await page
      .getByRole("group", { name: "직군" })
      .boundingBox())!;
    expect(chips.y).toBeGreaterThanOrEqual(sortAgain.y + sortAgain.height);
    await page.goto("/home");
    await waitForHomeLoaded(page);
    for (let i = 0; i < 2; i += 1) {
      await page.getByRole("button", { name: "인기순", exact: true }).click();
      await expect(page).toHaveURL(/order=POPULAR/);
      await post.click();
      await expect(page).toHaveURL(/\/post\/7$/);
      await expect(
        page.getByRole("article", { name: "고민 글" }),
      ).toBeVisible();
      if (i === 0) {
        // DS-US3-AC2: 무대는 글의 감정 색이고 큰 HP 막대와 게임 버튼이 있다.
        const article = page.getByRole("article", { name: "고민 글" });
        const stage = article.locator('[aria-label="몬스터"]').locator("..");
        await expect(stage).toHaveCSS("background-color", "rgb(241, 235, 255)");
        await expect(
          article.getByRole("progressbar", { name: "몬스터 HP" }),
        ).toHaveCSS("height", "22px");
        await expect(
          article.getByRole("button", { name: "공감 3", exact: true }),
        ).toHaveCSS("background-color", "rgb(255, 214, 10)");
        const monsterImage = article.getByRole("img", { name: /몬스터, / });
        await expect
          .poll(() =>
            monsterImage.evaluate(
              (image: HTMLImageElement) =>
                image.complete && image.naturalWidth > 0,
            ),
          )
          .toBe(true);
        await page.evaluate(() => document.fonts.ready);
        expect(
          await page.evaluate(
            () => document.documentElement.scrollWidth > innerWidth,
          ),
        ).toBe(false);
        // POLISH-AC12, AC17: 좁은 화면의 글 상세는 하단 메뉴 대신 댓글 입력줄이 화면 아래에 붙는다.
        const dock = page.locator("[data-comment-dock]");
        const mobileNav = page.getByRole("navigation", {
          name: "모바일 주요 메뉴",
        });
        if (viewport.width < 768) {
          await expect(mobileNav).toHaveCount(0);
          await expect(dock).toHaveCSS("position", "fixed");
          const box = (await dock.boundingBox())!;
          expect(Math.round(box.y + box.height)).toBe(viewport.height);
          // 맨 아래로 내려도 본문의 끝이 입력줄에 가리지 않는다.
          await page.evaluate(() =>
            window.scrollTo(0, document.documentElement.scrollHeight),
          );
          const lastBottom = await page.getByRole("main").evaluate((main) => {
            const flow = [...main.querySelectorAll("section, article")].filter(
              (element) => !element.closest("[data-comment-dock]"),
            );
            return Math.max(
              ...flow.map((element) => element.getBoundingClientRect().bottom),
            );
          });
          expect(lastBottom).toBeLessThanOrEqual(
            (await dock.boundingBox())!.y + 1,
          );
          // 여러 줄을 쓰면 입력 칸이 늘어나고(네 줄까지), 본문 아래의 여백이 따라가 끝이 가리지 않는다.
          const input = page.getByLabel("댓글", { exact: true });
          const oneLine = (await input.boundingBox())!.height;
          expect(oneLine).toBe(48);
          await input.fill(
            "첫 줄\n둘째 줄\n셋째 줄\n넷째 줄\n다섯째 줄\n여섯째 줄",
          );
          await expect
            .poll(async () => (await input.boundingBox())!.height)
            .toBe(128);
          await expect
            .poll(() =>
              page.evaluate(() =>
                getComputedStyle(document.documentElement)
                  .getPropertyValue("--comment-dock-height")
                  .trim(),
              ),
            )
            .toMatch(/^1[4-9]\dpx$/);
          await page.evaluate(() =>
            window.scrollTo(0, document.documentElement.scrollHeight),
          );
          const tallBottom = await page.getByRole("main").evaluate((main) => {
            const flow = [...main.querySelectorAll("section, article")].filter(
              (element) => !element.closest("[data-comment-dock]"),
            );
            return Math.max(
              ...flow.map((element) => element.getBoundingClientRect().bottom),
            );
          });
          expect(tallBottom).toBeLessThanOrEqual(
            (await dock.boundingBox())!.y + 1,
          );
          await input.fill("");
          await expect
            .poll(async () => (await input.boundingBox())!.height)
            .toBe(48);
          await page.evaluate(() => window.scrollTo(0, 0));
        } else {
          await expect(dock).toHaveCSS("position", "static");
        }
        await expect(
          page.getByRole("link", { name: "마음 이야기로 돌아가기" }),
        ).toBeVisible();
        await page.screenshot({
          path: testInfo.outputPath("post-detail.png"),
        });
      }
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
