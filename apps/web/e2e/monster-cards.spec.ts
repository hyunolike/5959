import { expect, test, type Page } from "@playwright/test";

const member = {
  id: 2,
  nickname: "로컬 검증회원",
  jobRole: "DEVELOPMENT",
  careerYear: "YEAR_3",
  onboarded: true,
};

const EMOTIONS = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
] as const;

const cards = [
  {
    cardNo: 128,
    emotion: "ANXIETY",
    grade: "LEGENDARY",
    role: "MINE",
    defeatedAt: "2026-10-12T03:00:00Z",
    myDamage: 0,
    helperCount: 6,
    postId: 31,
  },
  {
    cardNo: 121,
    emotion: "IRRITATION",
    grade: "RARE",
    role: "TOGETHER",
    defeatedAt: "2026-10-11T03:00:00Z",
    myDamage: 4,
    helperCount: null,
    postId: 28,
  },
  {
    cardNo: 117,
    emotion: "LONELINESS",
    grade: "COMMON",
    role: "TOGETHER",
    defeatedAt: "2026-10-09T03:00:00Z",
    myDamage: 3,
    helperCount: null,
    postId: 25,
  },
  {
    cardNo: 102,
    emotion: "SELF_DEPRECATION",
    grade: "RARE",
    role: "TOGETHER",
    defeatedAt: "2026-10-07T03:00:00Z",
    myDamage: 1,
    helperCount: null,
    postId: 20,
  },
];

const page1 = {
  items: cards,
  nextCursor: null,
  summary: {
    total: cards.length,
    byEmotion: EMOTIONS.map((emotion) => ({
      emotion,
      count: cards.filter((card) => card.emotion === emotion).length,
    })),
  },
};

/** 로그인한 회원으로 보이게 하고 도감 응답을 고정한다. 그 밖의 API는 쓰지 않는다. */
async function mockApi(page: Page) {
  await page.setExtraHTTPHeaders({
    cookie: "__Host-ogu_rt=local-review; __Host-ogu_ob=1",
  });
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    const data =
      path === "/api/members/me"
        ? member
        : path === "/api/members/me/monster-cards"
          ? page1
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
}

const noHorizontalOverflow = (page: Page) =>
  page.evaluate(
    () => document.documentElement.scrollWidth <= window.innerWidth,
  );

for (const viewport of [
  { width: 1440, height: 1000 },
  { width: 390, height: 844 },
  { width: 320, height: 640 },
]) {
  test(`US3-AC6 도감과 크게 본 카드가 화면을 넘지 않는다 (${viewport.width}px)`, async ({
    page,
  }) => {
    await page.setViewportSize(viewport);
    await mockApi(page);
    await page.goto("/cards");

    const list = page.getByRole("list", { name: "모은 카드 목록" });
    await expect(list.getByRole("button")).toHaveCount(4);
    await expect(page.getByText("모두 4장")).toBeVisible();
    await expect(
      page.getByText("아직 만나지 못한 몬스터: 무기력"),
    ).toBeVisible();
    expect(await noHorizontalOverflow(page)).toBe(true);

    await list.getByRole("button").first().click();
    const dialog = page.getByRole("dialog", { name: "불안 카드 No.128" });
    await expect(dialog).toBeVisible();
    const box = (await dialog.boundingBox())!;
    expect(box.x).toBeGreaterThanOrEqual(0);
    expect(box.x + box.width).toBeLessThanOrEqual(viewport.width);
    expect(await noHorizontalOverflow(page)).toBe(true);
  });
}

test("US3-AC1, US3-AC5 카드를 키보드로 열고 Esc로 닫으면 초점이 그 카드로 돌아온다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  await page.goto("/cards");

  const opener = page
    .getByRole("list", { name: "모은 카드 목록" })
    .getByRole("button")
    .nth(1);
  await opener.focus();
  await page.keyboard.press("Enter");

  const dialog = page.getByRole("dialog", { name: "짜증 카드 No.121" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByText("내가 준 피해")).toBeVisible();
  await expect(page.getByRole("button", { name: "닫기" })).toBeFocused();
  await expect(dialog.getByRole("link", { name: "글 보기" })).toHaveAttribute(
    "href",
    "/post/28",
  );

  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(opener).toBeFocused();
});

test("US3-AC2, US3-AC3 크게 본 카드는 포인터를 따라 기울고 등급에 따라 반짝임이 다르다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  await page.goto("/cards");
  const buttons = page
    .getByRole("list", { name: "모은 카드 목록" })
    .getByRole("button");

  // 목록에서 등급마다 반짝임의 세기가 다르다. 일반은 없다.
  const shine = (index: number) =>
    buttons
      .nth(index)
      .locator(".ogu-card-shine")
      .evaluate((element) => getComputedStyle(element).opacity);
  expect(Number(await shine(0))).toBeGreaterThan(0);
  expect(Number(await shine(1))).toBeGreaterThan(0);
  expect(Number(await shine(2))).toBe(0);

  await buttons.first().click();
  const card = page.locator(".ogu-card-tilt");
  const box = (await card.boundingBox())!;
  await page.mouse.move(box.x + box.width * 0.9, box.y + box.height * 0.1);

  await expect
    .poll(() => card.evaluate((el) => el.style.getPropertyValue("--card-x")))
    .toBe("90%");
  const transform = await card.evaluate(
    (element) => getComputedStyle(element).transform,
  );
  expect(transform).not.toBe("none");
});

test("US3-AC4 움직임 줄이기에서는 카드가 기울지 않는다", async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  await page.goto("/cards");

  await page
    .getByRole("list", { name: "모은 카드 목록" })
    .getByRole("button")
    .first()
    .click();
  const card = page.locator(".ogu-card-tilt");
  const box = (await card.boundingBox())!;
  await page.mouse.move(box.x + box.width * 0.9, box.y + box.height * 0.1);

  expect(
    await card.evaluate((el) => el.style.getPropertyValue("--card-x")),
  ).toBe("");
  expect(
    await card.evaluate((element) => getComputedStyle(element).transform),
  ).toBe("none");
});

test("US5-AC1 좁은 화면의 메뉴는 다섯 칸이고 고민 쓰기가 화면 한가운데다", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await mockApi(page);
  await page.goto("/cards");

  const menu = page.getByRole("navigation", { name: "모바일 주요 메뉴" });
  await expect(menu.getByRole("link")).toHaveCount(5);
  await expect(menu.getByRole("link", { name: "몬스터 도감" })).toHaveAttribute(
    "aria-current",
    "page",
  );
  const write = (await menu
    .locator("[data-navigation-primary]")
    .boundingBox())!;
  expect(Math.abs(write.x + write.width / 2 - 195)).toBeLessThanOrEqual(1);
  // 다섯 메뉴 모두 누르는 영역이 44px 이상이다.
  for (const link of await menu.getByRole("link").all()) {
    const box = (await link.boundingBox())!;
    expect(box.width).toBeGreaterThanOrEqual(44);
    expect(box.height).toBeGreaterThanOrEqual(44);
  }
});
