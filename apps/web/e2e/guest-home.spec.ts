import { expect, test } from "@playwright/test";

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
    const privateRequests: string[] = [];
    page.on("request", (request) => {
      const path = new URL(request.url()).pathname;
      if (path.startsWith("/api/")) privateRequests.push(path);
    });
    await page.goto("/login");
    await page
      .getByRole("link", { name: "먼저 둘러보기", exact: true })
      .click();
    await expect(page).toHaveURL(/\/home$/);
    await expect(
      page.getByRole("heading", {
        name: "마음 이야기는 로그인 후 볼 수 있어요",
      }),
    ).toBeVisible();
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
    expect(privateRequests).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
    // DS-US3-AC5: 다섯 감정의 몬스터 그림이 실제로 그려지고, 보호된 API는 부르지 않는다.
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
