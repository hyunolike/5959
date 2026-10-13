import { expect, test } from "@playwright/test";

test("BRAND-US1-AC1 원형 mask가 글자와 캐릭터를 자르지 않는다", async ({
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
  expect(pixels.upper).toBeGreaterThan(1000);
  expect(pixels.lower).toBeGreaterThan(5000);
});

for (const width of [390, 1440]) {
  test(`BRAND-US1-AC2 서비스 로고는 이름과 24/32px 레터링을 보존한다 (${width}px)`, async ({
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
    const wordmark = logo.locator('img[src="/brand/ogu-wordmark.svg"]');
    await expect(wordmark).toBeVisible();
    const box = await wordmark.boundingBox();
    expect(box?.height).toBe(width < 640 ? 24 : 32);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth > innerWidth,
      ),
    ).toBe(false);
  });
}
