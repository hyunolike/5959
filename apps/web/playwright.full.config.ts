import { defineConfig, devices } from "@playwright/test";

/**
 * 전체 흐름 E2E: infra/compose.e2e.yaml로 띄운 실제 API + DB(호스트 포트 18080)를 상대로 웹 서버를 띄운다.
 * playwright.config.ts(./e2e)와 달리 /api/health 등을 목(mock)하지 않는다 — 진짜 응답을 확인한다.
 */
export default defineConfig({
  testDir: "./e2e-full",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: "html",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        launchOptions: {
          executablePath: process.env.PLAYWRIGHT_CHROMIUM_PATH,
        },
      },
    },
  ],
  webServer: {
    command: "pnpm build && pnpm start",
    url: "http://localhost:3000",
    // 항상 이 config가 직접 띄운 서버를 쓴다. true(로컬 기본값)면 :3000에 이미 떠 있는 아무 서버에나
    // 붙어서 아래 webServer.env(API_ORIGIN=:18080 등)가 전혀 적용되지 않은 채로 "실제 API" 스위트가
    // 엉뚱한 백엔드(로컬 :8080이나 이전 빌드)를 상대로 조용히 돌 수 있다. 포트가 이미 쓰이고 있으면
    // 시끄럽게 실패하는 편이 맞다.
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      API_ORIGIN: process.env.API_ORIGIN ?? "http://localhost:18080",
      BFF_API_KEY: process.env.BFF_API_KEY ?? "e2e-bff-key",
      APP_ORIGIN: process.env.APP_ORIGIN ?? "http://localhost:3000",
      APP_ENV: "e2e",
      // 브라우저가 실시간 알림 스트림에 바로 붙을 API 주소(004 research R3). 호스트에서 보이는 주소여야 한다.
      SSE_PUBLIC_ORIGIN:
        process.env.SSE_PUBLIC_ORIGIN ??
        process.env.API_ORIGIN ??
        "http://localhost:18080",
    },
  },
});
