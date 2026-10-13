import { expect, test, type Page } from "@playwright/test";

// 브라우저 이벤트/API는 로컬 테스트 대역이다. OS의 실제 설치 완료를 검증하지 않는다.
const author = {
  id: 2,
  nickname: "검증회원",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
};
const item = {
  postId: 7,
  author,
  contentPreview: "로컬 테스트용 고민입니다. 내일 발표가 걱정돼요.",
  analysisStatus: "ANALYZED",
  monster: { emotion: "ANXIETY", hp: 7, maxHp: 10, status: "ALIVE" },
  likeCount: 2,
  likedByMe: false,
  commentCount: 0,
  createdAt: "2026-10-12T08:30:00Z",
};

async function mockApi(page: Page) {
  const state = {
    likeRequests: 0,
    failLike: false,
    holdLike: false,
    failFeed: false,
    releaseLike: () => {},
  };
  let liked = false;
  await page.setExtraHTTPHeaders({
    cookie: "__Host-ogu_rt=local-review; __Host-ogu_ob=1",
  });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    let data: unknown;
    let status = 200;
    if (path === "/api/posts/7/likes" || path === "/api/posts/7/likes/me") {
      state.likeRequests++;
      if (state.holdLike)
        await new Promise<void>((resolve) => {
          state.releaseLike = resolve;
        });
      if (state.failLike) status = 503;
      else {
        liked = method === "POST";
        data = { likedByMe: liked, likeCount: liked ? 3 : 2 };
      }
    } else if (path === "/api/members/me")
      data = { ...author, onboarded: true };
    else if (path === "/api/feed") {
      if (state.failFeed) status = 503;
      else data = { items: [{ ...item, likedByMe: liked }], nextCursor: null };
    } else if (path === "/api/posts/7")
      data = {
        ...item,
        likedByMe: liked,
        likeCount: liked ? 3 : 2,
        monster: { ...item.monster, hp: liked ? 6 : 7 },
        content: item.contentPreview,
        mine: false,
        commentTone: "WARM_ADVICE",
        myCommentCounted: false,
      };
    else if (path === "/api/raid")
      data = { boss: null, available: true, myDamage: 0, nextBossAt: null };
    else if (path === "/api/notifications/unread-count") data = { count: 0 };
    else if (path === "/api/members/me/emotion-stats")
      data = {
        totalMonsters: 0,
        defeatedMonsters: 0,
        defeatedTogether: 0,
        raidBossesDefeated: 0,
        topEmotion: null,
        distribution: [],
        weekly: [],
      };
    else if (
      path.endsWith("/comments") ||
      path.endsWith("/similar") ||
      path.startsWith("/api/members/me/")
    )
      data = { items: [], nextCursor: null };
    else status = 503;
    await route.fulfill({
      status,
      json:
        status === 200
          ? { success: true, data, error: null }
          : {
              success: false,
              data: null,
              error: {
                code: "TEST_UNAVAILABLE",
                message: "로컬 테스트용 오류",
              },
            },
    });
  });
  return state;
}

async function offerInstall(page: Page, outcome: "accepted" | "dismissed") {
  await page.evaluate((outcome) => {
    const state = window as unknown as {
      installCalls: number;
      finishInstall: () => void;
    };
    state.installCalls = 0;
    const choice = new Promise<{
      outcome: "accepted" | "dismissed";
      platform: string;
    }>((resolve) => {
      state.finishInstall = () => resolve({ outcome, platform: "web" });
    });
    const event = Object.assign(
      new Event("beforeinstallprompt", { cancelable: true }),
      {
        prompt: async () => {
          state.installCalls++;
        },
        userChoice: choice,
      },
    );
    window.dispatchEvent(event);
  }, outcome);
}

test("iPhone·인앱·standalone 안내와 차단된 저장소를 구분한다", async ({
  browser,
}, testInfo) => {
  const ios =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 26_0 like Mac OS X) AppleWebKit/605.1.15 Version/26.0 Mobile/15E148 Safari/604.1";
  for (const environment of ["ios", "inapp", "standalone", "blocked-storage"]) {
    const context = await browser.newContext({
      viewport: { width: 320, height: 844 },
      userAgent:
        environment === "inapp"
          ? `${ios} KAKAOTALK`
          : environment === "ios"
            ? ios
            : undefined,
    });
    if (environment === "standalone")
      await context.addInitScript(() =>
        Object.defineProperty(navigator, "standalone", { value: true }),
      );
    if (environment === "blocked-storage")
      await context.addInitScript(() => {
        Storage.prototype.getItem = () => {
          throw new DOMException("blocked", "SecurityError");
        };
        Storage.prototype.setItem = () => {
          throw new DOMException("blocked", "SecurityError");
        };
      });
    const page = await context.newPage();
    const errors: string[] = [];
    page.on("pageerror", (error) => errors.push(error.message));
    await mockApi(page);
    await page.goto("/home");
    await expect(page.getByText(item.contentPreview)).toBeVisible();
    const guide = page.getByRole("button", {
      name: "홈 화면 추가 방법",
      exact: true,
    });
    if (environment === "standalone") {
      await offerInstall(page, "accepted");
      await expect(guide).toHaveCount(0);
      await expect(
        page.getByRole("complementary", { name: "홈 화면 추가" }),
      ).toHaveCount(0);
    } else if (environment === "blocked-storage") {
      await offerInstall(page, "accepted");
      await page
        .getByRole("button", { name: "설치 안내 7일간 숨기기" })
        .click();
      await expect(
        page.getByText("오구오구를 홈 화면에서 만나세요"),
      ).toHaveCount(0);
      await expect(guide).toBeVisible();
    } else {
      if (environment === "inapp") {
        await offerInstall(page, "accepted");
        await expect(
          page.getByText("오구오구를 홈 화면에서 만나세요"),
        ).toHaveCount(0);
      }
      await guide.click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByText(
          environment === "ios" ? /웹 앱으로 열기/ : /기본 브라우저로 열기/,
        ),
      ).toBeVisible();
      await page.screenshot({
        path: testInfo.outputPath(`guide-${environment}-320.png`),
        fullPage: true,
      });
      await page.keyboard.press("Escape");
      await expect(guide).toBeFocused();
      expect(await page.evaluate(() => document.body.style.overflow)).not.toBe(
        "hidden",
      );
    }
    expect(errors).toEqual([]);
    await context.close();
  }
});

for (const width of [320, 390, 1440]) {
  test(`설치 취소·수락·뒤로 가기와 화면 폭 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: width === 1440 ? 1000 : 844 });
    await mockApi(page);
    await page.goto("/home");
    await expect(page.getByText(item.contentPreview)).toBeVisible();
    await expect(
      page.getByRole("button", { name: "홈 화면에 추가", exact: true }),
    ).toHaveCount(0);
    await offerInstall(page, "dismissed");
    const install = page.getByRole("button", {
      name: "홈 화면에 추가",
      exact: true,
    });
    await install.click();
    await expect(
      page.getByRole("button", { name: "브라우저 안내 여는 중" }),
    ).toBeDisabled();
    await page.evaluate(() =>
      (window as unknown as { finishInstall: () => void }).finishInstall(),
    );
    await expect(page.getByRole("status")).toContainText("설치를 취소했어요");
    expect(
      await page.evaluate(
        () => (window as unknown as { installCalls: number }).installCalls,
      ),
    ).toBe(1);
    await expect(page.getByText("오구오구를 홈 화면에서 만나세요")).toHaveCount(
      0,
    );
    await page.reload();
    await expect(
      page.getByRole("button", { name: "홈 화면 추가 방법", exact: true }),
    ).toBeVisible();
    await offerInstall(page, "accepted");
    await expect(install).toHaveCount(0); // 7일 숨김은 재방문에도 유지한다.
    const guide = page.getByRole("button", {
      name: "홈 화면 추가 방법",
      exact: true,
    });
    for (let i = 0; i < 2; i++) {
      await guide.click();
      await expect(page.getByRole("dialog")).toBeVisible();
      await expect(
        page.getByRole("button", { name: "닫기", exact: true }),
      ).toBeFocused();
      await page.keyboard.press("Shift+Tab");
      await expect(
        page.getByRole("button", { name: "브라우저 설치 안내 열기" }),
      ).toBeFocused();
      await page.goBack();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(guide).toBeFocused();
      await expect(page).toHaveURL(/\/home$/);
    }
    await guide.click();
    await page.getByRole("button", { name: "브라우저 설치 안내 열기" }).click();
    await page.evaluate(() =>
      (window as unknown as { finishInstall: () => void }).finishInstall(),
    );
    await page.getByRole("button", { name: "닫기", exact: true }).click();
    await expect(page.getByRole("status")).toContainText(
      "설치 완료 여부는 브라우저가 알려줍니다",
    );
    await expect(guide).toBeVisible(); // accepted는 실제 설치 완료가 아니다.
    await page.evaluate(() => window.dispatchEvent(new Event("appinstalled")));
    await expect(guide).toHaveCount(0);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    await page.screenshot({
      path: testInfo.outputPath(`home-${width}.png`),
      fullPage: true,
    });
  });
}

test("공감은 중복 요청 없이 실패를 복구하고 실제 성공 후 다시 취소한다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 320, height: 844 });
  const state = await mockApi(page);
  state.failLike = state.holdLike = true;
  await page.goto("/post/7");
  const button = page.getByRole("button", { name: /^공감 / });
  await expect(button).toHaveText("공감 2");
  await button.evaluate((node: HTMLButtonElement) => {
    node.click();
    node.click();
  });
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute("aria-busy", "true");
  await expect(button).toHaveText("공감 3");
  await expect(
    page.getByRole("progressbar", { name: "몬스터 HP" }),
  ).toHaveAttribute("aria-valuenow", "6");
  await expect.poll(() => state.likeRequests).toBe(1);
  state.releaseLike();
  await expect(
    page.getByRole("alert").filter({ hasText: "공감하지 못했어요" }),
  ).toBeVisible();
  await expect(button).toBeEnabled();
  await expect(button).toHaveText("공감 2");
  await expect(
    page.getByRole("progressbar", { name: "몬스터 HP" }),
  ).toHaveAttribute("aria-valuenow", "7");
  state.failLike = state.holdLike = false;
  await button.click();
  await expect(
    page.getByRole("status").filter({ hasText: "공감했어요" }),
  ).toHaveText("공감했어요.");
  await expect(button).toHaveAttribute("aria-pressed", "true");
  await button.click();
  await expect(
    page.getByRole("status").filter({ hasText: "취소했어요" }),
  ).toHaveText("공감을 취소했어요.");
  expect(state.likeRequests).toBe(3);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});

test("안내창을 뒤로·앞으로 재방문한 뒤 다시 열어도 뒤로 가기로 닫힌다", async ({
  page,
}) => {
  await mockApi(page);
  await page.goto("/home");
  const guide = page.getByRole("button", {
    name: "홈 화면 추가 방법",
    exact: true,
  });
  await guide.click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goForward();
  await guide.click();
  await page.goBack();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  expect(await page.evaluate(() => document.body.style.overflow)).not.toBe(
    "hidden",
  );
  await expect(guide).toBeFocused();
});

test("키보드 활동 탭과 실행 중 reduced-motion 변경", async ({ page }) => {
  await mockApi(page);
  await page.goto("/my");
  const first = page.getByRole("tab", { name: "내가 쓴 글" });
  const comments = page.getByRole("tab", { name: "내 댓글" });
  await first.focus();
  await page.keyboard.press("ArrowRight");
  await expect(comments).toBeFocused();
  await expect(first).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/tab=comments/);
  await expect(comments).toHaveAttribute("aria-selected", "true");
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.keyboard.press("End");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/tab=reports/);
  const indicator = page
    .getByRole("tablist")
    .locator('span[aria-hidden="true"]');
  await expect
    .poll(() => indicator.evaluate((node) => getComputedStyle(node).transform))
    .toMatch(/matrix\(1, 0, 0, 1,/);
  await expect
    .poll(() =>
      indicator.evaluate((node) => {
        const parent = node.parentElement!.getBoundingClientRect();
        return (
          Math.abs(
            node.getBoundingClientRect().left -
              parent.left -
              parent.width * 0.75,
          ) < 1
        );
      }),
    )
    .toBe(true);
});

test("피드 오류에서 실제 재시도로 복구한다", async ({ page }) => {
  const state = await mockApi(page);
  state.failFeed = true;
  await page.goto("/home");
  await expect(
    page.getByRole("alert").filter({ hasText: "피드를 불러오지 못했습니다" }),
  ).toBeVisible();
  state.failFeed = false;
  await page.getByRole("button", { name: "다시 불러오기" }).click();
  await expect(page.getByText(item.contentPreview)).toBeVisible();
});

test("manifest와 공개 아이콘을 제공하고 서비스 워커를 등록하지 않는다", async ({
  page,
  request,
}) => {
  const response = await request.get("/manifest.webmanifest");
  expect(response.ok()).toBe(true);
  const manifest = await response.json();
  expect(manifest).toMatchObject({
    id: "/",
    start_url: "/home",
    scope: "/",
    display: "standalone",
  });
  for (const icon of manifest.icons)
    expect((await request.get(icon.src)).ok()).toBe(true);
  expect((await request.get("/brand/ogu-symbol.webp")).ok()).toBe(true);
  await page.goto("/login");
  await expect(page.locator('link[rel="manifest"]')).toHaveAttribute(
    "href",
    "/manifest.webmanifest",
  );
  await expect(page.locator('link[rel="apple-touch-icon"]')).toHaveAttribute(
    "href",
    "/brand/ogu-lettering-v1-apple-touch-180.png",
  );
  expect(
    await page.evaluate(
      async () => (await navigator.serviceWorker.getRegistrations()).length,
    ),
  ).toBe(0);
  const cdp = await page.context().newCDPSession(page);
  const { installabilityErrors } = await cdp.send(
    "Page.getInstallabilityErrors",
  );
  expect(
    installabilityErrors.filter((error: { errorId: string }) =>
      /manifest|icon/.test(error.errorId),
    ),
  ).toEqual([]);
});
