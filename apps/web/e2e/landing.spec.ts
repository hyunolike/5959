import { expect, test } from "@playwright/test";

test("SEO-AC1 첫 접속은 로그인 없이 서비스 소개를 보여 주고 로그인으로 이어진다", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/$/);
  await expect(
    page.getByRole("heading", { level: 1, name: /오구오구/ }),
  ).toBeVisible();
  // 대표 주소는 서비스 출처 그대로다(끝의 `/`는 Next가 떼어 낸다).
  await expect(page.locator('link[rel="canonical"]')).toHaveAttribute(
    "href",
    /^https?:\/\/[^/]+\/?$/,
  );
  await expect(
    page.locator('meta[name="naver-site-verification"]'),
  ).toHaveCount(1);
  await expect(page.locator('link[rel="icon"][href^="/icon.png"]')).toHaveCount(
    1,
  );
  await page.getByRole("link", { name: "시작하기", exact: true }).click();
  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole("link", { name: "구글로 계속하기" }),
  ).toBeVisible();
});

test("SEO-AC4 robots.txt와 sitemap.xml이 열리고 로그인 경로는 긁지 않게 한다", async ({
  request,
}) => {
  const robots = await request.get("/robots.txt");
  expect(robots.status()).toBe(200);
  const body = await robots.text();
  expect(body).toContain("Disallow: /api/");
  expect(body).toMatch(/Sitemap: https?:\/\/.+\/sitemap\.xml/);

  const sitemap = await request.get("/sitemap.xml");
  expect(sitemap.status()).toBe(200);
  expect(await sitemap.text()).toContain("<loc>");
});

test("US1-AC2 로그인한 사용자는 첫 접속에서 홈으로 이동한다", async ({
  request,
}) => {
  const response = await request.get("/", {
    headers: { cookie: "__Host-ogu_rt=test-session; __Host-ogu_ob=1" },
    maxRedirects: 0,
  });
  expect(response.status()).toBe(307);
  expect(new URL(response.headers().location, response.url()).pathname).toBe(
    "/home",
  );
});

test("016 US5-AC2 움직임 줄이기를 켜면 사용법 장면이 멈춘 그림이 된다", async ({
  page,
}) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.goto("/");
  const scene = page.getByRole("img", {
    name: /고민을 쓰면 몬스터가 나타나고/,
  });
  await expect(scene).toBeVisible();
  const animations = await scene
    .locator(".ogu-loop-panel, .ogu-loop-sprite, .ogu-loop-hp-fill")
    .evaluateAll((nodes) =>
      nodes.map((node) => getComputedStyle(node).animationName),
    );
  expect(animations.length).toBeGreaterThan(0);
  expect(new Set(animations)).toEqual(new Set(["none"]));
});

test("016 US5-AC6 몬스터 그림을 받지 못해도 글로 쓴 사용법은 보인다", async ({
  page,
}) => {
  await page.route("**/monsters/**", (route) => route.abort());
  await page.route("**/_next/image**", (route) =>
    route.request().url().includes("monsters")
      ? route.abort()
      : route.continue(),
  );
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "오구오구는 이렇게 써요" }),
  ).toBeVisible();
  await expect(page.getByText("익명으로 고민을 적어요")).toBeVisible();
});
