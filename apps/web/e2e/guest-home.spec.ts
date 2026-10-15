import { expect, test, type Page } from "@playwright/test";

const story = {
  postId: 7,
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
  contentPreview: "로컬 검증용 글입니다. 내일 발표를 앞두고 조금 긴장돼요.",
  analysisStatus: "ANALYZED",
  monster: { emotion: "ANXIETY", hp: 2, maxHp: 10, status: "ALIVE" },
  likeCount: 3,
  commentCount: 2,
  createdAt: "2026-10-12T08:30:00Z",
};

/** 로그인 없이 받는 최근 글(011)을 가짜 API로 채운다. 다른 `/api/` 요청은 그대로 둔다. */
async function mockPublicFeed(page: Page, items: unknown[]) {
  await page.route("**/api/public/feed", (route) =>
    route.fulfill({ json: { success: true, data: { items }, error: null } }),
  );
}

test("GUEST-US1-AC2 안내 중 새로고침 후 뒤로 가기는 중복 홈 없이 이전 로그인으로 돌아간다", async ({
  page,
}) => {
  await page.goto("/login");
  await page.getByRole("link", { name: "먼저 둘러보기" }).click();
  await expect(page).toHaveURL(/\/home$/);
  await page
    .getByRole("main")
    .getByRole("button", { name: "고민 쓰기", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.reload();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goBack();
  await expect(page).toHaveURL(/\/login$/);
});

for (const width of [320, 390, 1440]) {
  test(`GUEST-US1-AC1/2/4 익명 진입·메뉴·취소·뒤로 가기·새로고침 (${width}px)`, async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width, height: 844 });
    await page.emulateMedia({ reducedMotion: "reduce" });
    const apiRequests: string[] = [];
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (path.startsWith("/api/")) apiRequests.push(path);
    });
    await mockPublicFeed(page, [
      story,
      {
        ...story,
        postId: 8,
        contentPreview: "로컬 검증용 분석 중인 글입니다.",
        analysisStatus: "PENDING",
        monster: null,
      },
    ]);
    await page.goto("/login");
    await page
      .getByRole("link", { name: "먼저 둘러보기", exact: true })
      .click();
    await expect(page).toHaveURL(/\/home$/);
    // 011 US1-AC1, US1-AC3: 둘러보기만 눌러도 최근 글이 닉네임 없이 보인다
    const stories = page.getByRole("region", { name: "최근 이야기" });
    await expect(stories.getByRole("listitem")).toHaveCount(2);
    await expect(stories.getByText(story.contentPreview)).toBeVisible();
    await expect(stories.getByText("개발 · 3년차").first()).toBeVisible();
    await expect(stories.getByText("분석 중", { exact: true })).toBeVisible();
    await expect(page.locator('a[href^="/post/"]')).toHaveCount(0);
    const nav = page.getByRole("navigation", {
      name: width < 768 ? "모바일 주요 메뉴" : "주요 메뉴",
    });
    await expect(
      nav.getByRole("link", { name: "마음 이야기", exact: true }),
    ).toHaveAttribute("aria-current", "page");
    for (const label of ["고민 쓰기", "함께 이겨내기", "내 마음 기록"]) {
      const trigger = nav.getByRole("link", { name: label, exact: true });
      await trigger.click();
      const dialog = page.getByRole("dialog", { name: "로그인이 필요해요" });
      await expect(dialog).toBeVisible();
      await expect(
        dialog.getByRole("button", { name: "계속 둘러보기" }),
      ).toBeFocused();
      await dialog.getByRole("button", { name: "로그인하기" }).press("Tab");
      await expect(
        dialog.getByRole("button", { name: "계속 둘러보기" }),
      ).toBeFocused();
      await page.keyboard.press("Escape");
      await expect(dialog).toHaveCount(0);
      await expect(trigger).toBeFocused();
      await expect(page).toHaveURL(/\/home$/);
    }
    const write = page
      .getByRole("main")
      .getByRole("button", { name: "고민 쓰기", exact: true });
    for (let index = 0; index < 3; index++) {
      await write.dblclick();
      await expect(page.getByRole("dialog")).toHaveCount(1);
      await page.goBack();
      await expect(page.getByRole("dialog")).toHaveCount(0);
      await expect(page).toHaveURL(/\/home$/);
    }
    await write.click();
    await page.reload();
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(
      page.getByRole("heading", { name: "마음 이야기", exact: true }),
    ).toBeVisible();
    // 로그인 없이 부르는 것은 공개 피드 하나뿐이다(011 US2-AC5).
    expect([...new Set(apiRequests)]).toEqual(["/api/public/feed"]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    // DS-US3-AC5: 다섯 감정의 몬스터 그림이 실제로 그려진다. 목록의 몬스터는 약해진 모습이라 세지 않는다.
    const monsters = page.getByRole("img", { name: /몬스터, 멀쩡함$/ });
    await expect(monsters).toHaveCount(5);
    await expect
      .poll(() =>
        monsters.evaluateAll((images) =>
          (images as HTMLImageElement[]).every(
            (image) => image.complete && image.naturalWidth > 0,
          ),
        ),
      )
      .toBe(true);
    await page.screenshot({
      path: testInfo.outputPath(`guest-home-${width}.png`),
      fullPage: true,
    });
  });
}

test("GUEST-US1-AC3 확인은 내부 목적지를 보존하고 뒤로 가기는 안내 없는 홈으로 돌아온다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/home");
  await page
    .getByRole("main")
    .getByRole("button", { name: "고민 쓰기", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "로그인하기" })
    .dblclick();
  await expect(page).toHaveURL(/\/login\?next=%2Fwrite$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goBack();
  await expect(page).toHaveURL(/\/home$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page.goForward();
  await expect(page).toHaveURL(/\/login\?next=%2Fwrite$/);
});

test("011 US1-AC4 글을 누르면 로그인 안내가 열리고 로그인은 그 글을 기억한다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockPublicFeed(page, [story]);
  await page.goto("/home");
  const open = page
    .getByRole("region", { name: "최근 이야기" })
    .getByRole("button", { name: "로그인하고 이 글 읽기" });
  await open.click();
  const dialog = page.getByRole("dialog", { name: "로그인이 필요해요" });
  await dialog.getByRole("button", { name: "계속 둘러보기" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page).toHaveURL(/\/home$/);
  await open.click();
  await dialog.getByRole("button", { name: "로그인하기" }).click();
  await expect(page).toHaveURL(/\/login\?next=%2Fpost%2F7$/);
});

test("011 US1-AC5/6/7 스무 개 안내, 빈 목록, 불러오기 실패에도 로그인으로 가는 길이 남는다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const stories = page.getByRole("region", { name: "최근 이야기" });
  const login = page.getByRole("button", { name: "로그인하고 이야기 보기" });

  // 가짜 API가 없으면 BFF가 닿지 못해 실패 안내가 보인다. 다시 해 보느라 몇 초 걸린다.
  await page.goto("/home");
  await expect(stories.getByRole("alert")).toContainText(
    "이야기를 불러오지 못했어요",
    { timeout: 15_000 },
  );
  await expect(login).toBeVisible();
  await expect(page.getByText("오구오구는 이렇게 써요")).toBeVisible();

  // 웹이 먼저 배포돼 옛 API가 401을 줘도 방문자를 로그인 화면으로 보내지 않는다.
  await page.route("**/api/public/feed", (route) =>
    route.fulfill({
      status: 401,
      json: {
        success: false,
        data: null,
        error: { code: "UNAUTHORIZED", message: "인증이 필요합니다" },
      },
    }),
  );
  await page.reload();
  await expect(stories.getByRole("alert")).toBeVisible();
  await expect(page).toHaveURL(/\/home$/);
  await page.unroute("**/api/public/feed");

  await mockPublicFeed(page, []);
  await page.reload();
  await expect(stories.getByText("아직 올라온 이야기가 없어요.")).toBeVisible();
  await expect(login).toBeVisible();

  await page.unroute("**/api/public/feed");
  await mockPublicFeed(
    page,
    Array.from({ length: 20 }, (_, index) => ({ ...story, postId: index + 1 })),
  );
  await page.reload();
  await expect(stories.getByRole("listitem")).toHaveCount(20);
  await expect(
    stories.getByText(/더 많은 이야기는 로그인하면 볼 수 있어요/),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth > innerWidth,
    ),
  ).toBe(false);
});

test("011 US2-AC6 홈은 검색 엔진에 색인하지 말라고 알린다", async ({
  page,
}) => {
  await page.goto("/home");
  await expect(page.locator('meta[name="robots"]')).toHaveAttribute(
    "content",
    /noindex/,
  );
});

test("GUEST-US1-AC3 상세·개인 메뉴 직접 URL은 로그인과 원래 내부 경로를 요구한다", async ({
  request,
}) => {
  for (const path of [
    "/write",
    "/post/1",
    "/my",
    "/my/edit",
    "/raid",
    "/notifications",
    "/settings",
  ]) {
    const response = await request.get(path, { maxRedirects: 0 });
    expect(response.status()).toBe(307);
    const target = new URL(response.headers().location, response.url());
    expect(target.pathname).toBe("/login");
    expect(target.searchParams.get("next")).toBe(path);
  }
});

test("016 US5-AC2 둘러보기의 사용법 장면도 움직임 줄이기에서 멈춘다", async ({
  page,
}) => {
  await mockPublicFeed(page, [story]);
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/home");
  const scene = page.getByRole("img", {
    name: /고민을 쓰면 몬스터가 나타나고/,
  });
  await expect(scene).toBeVisible();
  const animations = await scene
    .locator(".ogu-loop-panel, .ogu-loop-sprite, .ogu-loop-hp-fill")
    .evaluateAll((nodes) =>
      nodes.map((node) => getComputedStyle(node).animationName),
    );
  expect(new Set(animations)).toEqual(new Set(["none"]));
});
