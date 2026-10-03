/**
 * 3D 몬스터를 정지 이미지 20장(감정 5 × 단계 4)으로 캡처한다(T054, research R11).
 *
 *   pnpm --filter web render:monsters
 *
 * Vite로 scripts/monster-capture 하네스를 띄우고(Next 앱 밖이라 배포되지 않는다),
 * Playwright Chromium이 투명 배경 512×512로 찍어 public/monsters/{emotion}-{stage}.png에 쓴다.
 * 피드 카드와 3D를 못 쓰는 상세 화면(US5-AC3, US5-AC4)이 이 이미지를 쓴다.
 * 외형(appearance.ts)이나 장면(monster-3d.tsx)을 바꾸면 다시 돌려서 커밋한다.
 *
 * Node 22.18 이상이 필요하다. 이 파일을 `node`로 바로 돌리므로 Node의 타입 지우기
 * (type stripping, 22.18부터 기본으로 켜짐)에 기댄다. package.json에 engines를 두면
 * Vercel이 그 범위로 런타임 Node 버전을 고르므로, 빌드 런타임은 건드리지 않고 여기서만 확인한다.
 */
import { mkdir, stat } from "node:fs/promises";
import path from "node:path";

import { chromium } from "@playwright/test";
import react from "@vitejs/plugin-react";
import { createServer } from "vite";

const [major, minor] = process.versions.node.split(".").map(Number);
if (major < 22 || (major === 22 && minor < 18)) {
  console.error(
    `render:monsters는 Node 22.18 이상이 필요하다(지금 ${process.versions.node}).`,
  );
  process.exit(1);
}

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
// launch가 실패해도 Vite 서버는 닫는다.
let browser: Awaited<ReturnType<typeof chromium.launch>> | undefined;
try {
  browser = await chromium.launch({
    executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH,
    args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"],
  });
  const page = await browser.newPage({
    viewport: { width: SIZE, height: SIZE },
    deviceScaleFactor: 1,
  });
  // 리스너 안에서 던지면 잡을 곳이 없다. 모아 두었다가 장면마다 확인한다.
  const pageErrors: Error[] = [];
  page.on("pageerror", (error) => pageErrors.push(error));
  await mkdir(outDir, { recursive: true });

  let total = 0;
  for (const emotion of EMOTIONS) {
    for (const stage of STAGES) {
      pageErrors.length = 0;
      await page.goto(`${baseUrl}?emotion=${emotion}&stage=${stage}`);
      try {
        await page.waitForFunction(
          () =>
            (window as unknown as { __monsterReady?: boolean })
              .__monsterReady === true,
          undefined,
          { timeout: 30_000 },
        );
      } catch (error) {
        throw new Error(
          `${emotion}-${stage} 장면을 그리지 못했다: ${pageErrors.map(String).join("; ") || String(error)}`,
        );
      }
      if (pageErrors.length > 0) {
        throw new Error(
          `${emotion}-${stage} 장면에서 오류가 났다: ${pageErrors.map(String).join("; ")}`,
        );
      }
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
} catch (error) {
  console.error(error);
  process.exitCode = 1;
} finally {
  await browser?.close();
  await server.close();
}
