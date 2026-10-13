/**
 * Existing ogu-hello.webp + our font-free vector wordmark → install PNGs.
 * Run: pnpm --dir apps/web exec node scripts/generate-brand-icons.mjs
 * Chromium must be installed through the existing Playwright toolchain.
 * The character source is read only. No remote requests or image generation.
 * Bump v1 filenames when changing the published composition; installed-device
 * icon refresh remains controlled by the browser/OS and is not guaranteed.
 */
import { chromium } from "@playwright/test";
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const web = fileURLToPath(new URL("../", import.meta.url));
const character = `data:image/webp;base64,${(await readFile(`${web}public/brand/ogu-hello.webp`)).toString("base64")}`;
const wordmark = `data:image/svg+xml;base64,${(await readFile(`${web}public/brand/ogu-wordmark.svg`)).toString("base64")}`;
const outputs = [
  ["pwa/ogu-lettering-v1-512.png", 512, false],
  ["pwa/ogu-lettering-v1-192.png", 192, false],
  ["brand/ogu-lettering-v1-apple-touch-180.png", 180, false],
  ["pwa/ogu-lettering-v1-maskable-512.png", 512, true],
];
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage();
  await page.route("**/*", (route) => route.abort());
  for (const [file, size, maskable] of outputs) {
    const result = await page.evaluate(
      async ({ character, wordmark, size, maskable }) => {
        const load = (src) =>
          new Promise((resolve, reject) => {
            const image = new Image();
            image.onload = () => resolve(image);
            image.onerror = reject;
            image.src = src;
          });
        const [logo, ogu] = await Promise.all([
          load(wordmark),
          load(character),
        ]);
        const canvas = document.createElement("canvas");
        canvas.width = canvas.height = size;
        const context = canvas.getContext("2d");
        context.fillStyle = "#FBF9F4";
        context.fillRect(0, 0, size, size);
        context.save();
        context.scale(size / 512, size / 512);
        context.imageSmoothingQuality = "high";
        const logoWidth = maskable ? 308 : 384;
        const characterWidth = maskable ? 224 : 304;
        context.drawImage(
          logo,
          (512 - logoWidth) / 2,
          maskable ? 112 : 48,
          logoWidth,
          logoWidth / 4,
        );
        context.drawImage(
          ogu,
          (512 - characterWidth) / 2,
          maskable ? 196 : 168,
          characterWidth,
          characterWidth,
        );
        context.restore();
        const pixels = context.getImageData(0, 0, size, size).data;
        let outsideSafePixels = 0;
        if (maskable) {
          for (let y = 0; y < size; y++)
            for (let x = 0; x < size; x++) {
              const offset = (y * size + x) * 4;
              const difference = Math.max(
                Math.abs(pixels[offset] - 251),
                Math.abs(pixels[offset + 1] - 249),
                Math.abs(pixels[offset + 2] - 244),
              );
              if (
                difference >= 24 &&
                Math.hypot(x + 0.5 - size / 2, y + 0.5 - size / 2) > size * 0.4
              )
                outsideSafePixels++;
            }
        }
        return {
          png: canvas.toDataURL("image/png").split(",")[1],
          outsideSafePixels,
        };
      },
      { character, wordmark, size, maskable },
    );
    if (result.outsideSafePixels)
      throw new Error(`${file}: foreground outside maskable safe area`);
    await writeFile(`${web}public/${file}`, Buffer.from(result.png, "base64"));
    console.log(
      `${file}: ${size}x${size}${maskable ? ", protected foreground verified" : ""}`,
    );
  }
} finally {
  await browser.close();
}
