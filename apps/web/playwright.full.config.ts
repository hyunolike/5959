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
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
    env: {
      API_ORIGIN: process.env.API_ORIGIN ?? "http://localhost:18080",
      BFF_API_KEY: process.env.BFF_API_KEY ?? "e2e-bff-key",
      APP_ORIGIN: process.env.APP_ORIGIN ?? "http://localhost:3000",
      APP_ENV: "e2e",
    },
  },
});
