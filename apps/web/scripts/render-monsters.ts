/**
 * 3D 몬스터를 정지 이미지 20장(감정 5 × 단계 4)으로 캡처한다(T054, research R11).
 *
 *   pnpm --filter web render:monsters
 *
 * Vite로 scripts/monster-capture 하네스를 띄우고(Next 앱 밖이라 배포되지 않는다),
 * Playwright Chromium이 투명 배경 512×512로 찍어 public/monsters/{emotion}-{stage}.png에 쓴다.
 * 피드 카드와 3D를 못 쓰는 상세 화면(US5-AC3, US5-AC4)이 이 이미지를 쓴다.
 * 외형(appearance.ts)이나 장면(monster-3d.tsx)을 바꾸면 다시 돌려서 커밋한다.
 */
import { mkdir, stat } from "node:fs/promises";
import path from "node:path";

import { chromium } from "@playwright/test";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const EMOTIONS = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
] as const;
const STAGES = ["full", "hurt", "weak", "defeated"] as const;
const SIZE = 512;

const webRoot = path.resolve(import.meta.dirname, "..");
const outDir = path.join(webRoot, "public", "monsters");

const server = await createServer({
  configFile: false,
  root: path.join(webRoot, "scripts", "monster-capture"),
  plugins: [react()],
  resolve: { alias: { "@": path.join(webRoot, "src") } },
  server: { host: "127.0.0.1", port: 0, strictPort: false },
  logLevel: "warn",
});
await server.listen();
const baseUrl = server.resolvedUrls?.local[0];
if (!baseUrl) {
  throw new Error("Vite 하네스 주소를 얻지 못했다");
}

// 헤드리스 Chromium은 GPU가 없어 SwiftShader(소프트웨어 WebGL)로 그린다.
const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH,
  args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
});

try {
  const page = await browser.newPage({
    viewport: { width: SIZE, height: SIZE },
    deviceScaleFactor: 1,
  });
  page.on("pageerror", (error) => {
    throw error;
  });
  await mkdir(outDir, { recursive: true });

  let total = 0;
  for (const emotion of EMOTIONS) {
    for (const stage of STAGES) {
      await page.goto(`${baseUrl}?emotion=${emotion}&stage=${stage}`);
      await page.waitForFunction(
        () =>
          (window as unknown as { __monsterReady?: boolean }).__monsterReady ===
          true,
        undefined,
        { timeout: 30_000 },
      );
      const file = path.join(outDir, `${emotion.toLowerCase()}-${stage}.png`);
      await page
        .locator("canvas")
        .screenshot({ path: file, omitBackground: true });
      const { size } = await stat(file);
      total += size;
      console.log(
        `${path.relative(webRoot, file)} ${(size / 1024).toFixed(1)} KiB`,
      );
    }
  }
  console.log(
    `총 ${EMOTIONS.length * STAGES.length}장, ${(total / 1024).toFixed(1)} KiB`,
  );
} finally {
  await browser.close();
  await server.close();
}
