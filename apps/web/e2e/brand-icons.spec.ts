import { expect, test } from "@playwright/test";

test("BRAND-US1-AC1 원형 mask가 심볼을 자르지 않는다", async ({
  page,
  request,
}) => {
  const manifest = await (await request.get("/manifest.webmanifest")).json();
  const icon = manifest.icons.find(
    (value: { purpose: string }) => value.purpose === "maskable",
  );
  await page.goto("/login");
  const pixels = await page.evaluate(async (source: string) => {
    const image = await createImageBitmap(await (await fetch(source)).blob());
    const canvas = document.createElement("canvas");
    canvas.width = image.width;
    canvas.height = image.height;
    const context = canvas.getContext("2d")!;
    context.drawImage(image, 0, 0);
    const data = context.getImageData(0, 0, image.width, image.height).data;
    const background = Array.from(data.subarray(0, 3));
    let outside = 0;
    let upper = 0;
    let lower = 0;
    for (let y = 0; y < image.height; y++)
      for (let x = 0; x < image.width; x++) {
        const offset = (y * image.width + x) * 4;
        if (
          Math.max(
            ...background.map((value, channel) =>
              Math.abs(value - data[offset + channel]),
            ),
          ) < 24
        )
          continue;
        if (
          Math.hypot(x + 0.5 - image.width / 2, y + 0.5 - image.height / 2) >
          image.width * 0.4
        )
          outside++;
        if (y < image.height * 0.38) upper++;
        else lower++;
      }
    return { width: image.width, height: image.height, outside, upper, lower };
  }, icon.src);
  expect(pixels.width).toBe(512);
  expect(pixels.height).toBe(512);
  expect(pixels.outside).toBe(0);
  // 심볼은 잎(위)과 얼굴(아래)이다. 둘 다 그려져 있다.
  expect(pixels.upper).toBeGreaterThan(1000);
  expect(pixels.lower).toBeGreaterThan(5000);
});

for (const width of [390, 1440]) {
  test(`LOGO-AC1 서비스 로고는 이름이 읽히고 28/32px 높이로 놓인다 (${width}px)`, async ({
    page,
  }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto("/login");
    const logo = page
      .getByRole("banner")
      .getByRole("img", { name: "오구오구", exact: true });
    await expect(logo).toBeVisible();
    await expect(
      page.getByRole("link", { name: "오구오구 홈", exact: true }),
    ).toHaveAttribute("href", "/login");
    const image = logo.locator('img[src="/brand/ogu-logo.svg"]');
    await expect(image).toBeVisible();
    await expect
      .poll(() =>
        image.evaluate(
          (element: HTMLImageElement) =>
            element.complete && element.naturalWidth > 0,
        ),
      )
      .toBe(true);
    const box = (await image.boundingBox())!;
    expect(box.height).toBe(width < 640 ? 28 : 32);
    // 가로형 원본의 비율(304:64)을 지킨다.
    expect(Math.abs(box.width / box.height - 304 / 64)).toBeLessThan(0.05);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
  });
}

test("LOGO-AC5 공유 이미지는 서비스 주소의 절대 경로로 실리고 1200×630 PNG로 열린다", async ({
  page,
  request,
}) => {
  await page.goto("/login");
  const content = await page
    .locator('meta[property="og:image"]')
    .getAttribute("content");
  expect(content).toMatch(/^https?:\/\/[^/]+\/opengraph-image\b/);
  expect(
    await page
      .locator('meta[property="og:image:width"]')
      .getAttribute("content"),
  ).toBe("1200");
  const image = await request.get(
    new URL(content!).pathname + new URL(content!).search,
  );
  expect(image.ok()).toBe(true);
  expect(image.headers()["content-type"]).toContain("image/png");
});
