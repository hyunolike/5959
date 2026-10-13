/**
 * 로고의 원본 SVG(scripts/brand)에서 설치 아이콘, 파비콘, 공유 이미지를 PNG로 만든다.
 * 실행: pnpm --dir apps/web exec node scripts/generate-brand-icons.mjs
 * Playwright의 Chromium이 깔려 있어야 한다. 원격 요청을 하지 않는다.
 *
 * 원본은 codex가 그린 것이다(docs/DESIGN_SYSTEM.md "에셋과 출처").
 *   icon.svg               파비콘. 흰 둥근 타일 위의 심볼(어두운 탭에서도 보이게)
 *   app-icon.svg           설치 아이콘. 흰 바탕에 심볼
 *   app-icon-maskable.svg  가장자리가 잘려도 되는 설치 아이콘. 심볼이 가운데 안전 영역 안에 있다
 *   og-image.svg           공유 이미지(1200×630)
 *
 * 만든 뒤 원본의 해시를 scripts/brand/sources.json에 적는다(brand-assets.test.ts가 견준다).
 *
 * 설치 아이콘의 모양을 바꾸면 파일 이름의 판(v2)을 올린다. 파비콘은 root-metadata.ts의 `?v=`를 올린다. 설치된 기기의 아이콘을 바꾸는 것은
 * 브라우저와 OS라서 바로 바뀐다고 장담할 수 없다.
 */
import { chromium } from "@playwright/test";
import { createHash } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const web = fileURLToPath(new URL("../", import.meta.url));
const outputs = [
  ["icon.svg", "src/app/icon.png", 96, 96],
  ["app-icon.svg", "public/pwa/ogu-logo-v2-192.png", 192, 192],
  ["app-icon.svg", "public/pwa/ogu-logo-v2-512.png", 512, 512],
  ["app-icon.svg", "public/brand/ogu-logo-v2-apple-touch-180.png", 180, 180],
  [
    "app-icon-maskable.svg",
    "public/pwa/ogu-logo-v2-maskable-512.png",
    512,
    512,
    "maskable",
  ],
  ["og-image.svg", "src/app/opengraph-image.png", 1200, 630],
];

const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.route("**/*", (route) => route.abort());
  for (const [source, file, width, height, kind] of outputs) {
    const svg = await readFile(`${web}scripts/brand/${source}`);
    const result = await page.evaluate(
      async ({ svg, width, height, maskable }) => {
        const image = new Image();
        await new Promise((resolve, reject) => {
          image.onload = resolve;
          image.onerror = reject;
          image.src = `data:image/svg+xml;base64,${svg}`;
        });
        const canvas = document.createElement("canvas");
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext("2d");
        context.imageSmoothingQuality = "high";
        context.drawImage(image, 0, 0, width, height);
        let outsideSafePixels = 0;
        if (maskable) {
          // 원형으로 잘려도 심볼이 남는지 본다. 바탕과 다른 점이 지름 80% 원 밖에 있으면 안 된다.
          const pixels = context.getImageData(0, 0, width, height).data;
          const background = [pixels[0], pixels[1], pixels[2]];
          for (let y = 0; y < height; y++)
            for (let x = 0; x < width; x++) {
              const offset = (y * width + x) * 4;
              const difference = Math.max(
                ...background.map((value, channel) =>
                  Math.abs(value - pixels[offset + channel]),
                ),
              );
              if (
                difference >= 24 &&
                Math.hypot(x + 0.5 - width / 2, y + 0.5 - height / 2) >
                  width * 0.4
              )
                outsideSafePixels++;
            }
        }
        return {
          png: canvas.toDataURL("image/png").split(",")[1],
          outsideSafePixels,
        };
      },
      {
        svg: svg.toString("base64"),
        width,
        height,
        maskable: kind === "maskable",
      },
    );
    if (result.outsideSafePixels)
      throw new Error(`${file}: 심볼이 안전 영역 밖으로 나간다`);
    await writeFile(`${web}${file}`, Buffer.from(result.png, "base64"));
    console.log(
      `${file}: ${width}x${height}${kind === "maskable" ? ", 안전 영역 확인" : ""}`,
    );
  }
  // 그때의 원본 해시를 적어 둔다. 원본만 고치고 PNG를 다시 만들지 않으면 테스트가 알아챈다.
  const sources = {};
  for (const source of [...new Set(outputs.map(([name]) => name))].sort()) {
    sources[source] = createHash("sha256")
      .update(await readFile(`${web}scripts/brand/${source}`))
      .digest("hex");
  }
  await writeFile(
    `${web}scripts/brand/sources.json`,
    `${JSON.stringify(sources, null, 2)}\n`,
  );
} finally {
  await browser.close();
}
