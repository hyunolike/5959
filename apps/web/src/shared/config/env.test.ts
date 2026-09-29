// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// env.ts는 불러오는 순간 process.env를 검증한다. 빌드(next build)와 기동도 이
// 모듈을 불러오므로, 여기서 예외가 나면 빌드와 기동이 실패한다.
async function loadEnv() {
  vi.resetModules();
  return (await import("./env")).env;
}

const PRODUCTION_SECRET = "prod-oauth-state-secret-0123456789abcdefgh";

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  for (const name of ["VERCEL_ENV", "APP_ENV", "OAUTH_STATE_SECRET"]) {
    vi.stubEnv(name, undefined);
  }
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("env 검증", () => {
  it("VERCEL_ENV=production인데 APP_ENV=e2e면 가짜 OAuth 분기가 켜지므로 검증에 실패한다", async () => {
    vi.stubEnv("VERCEL_ENV", "production");
    vi.stubEnv("APP_ENV", "e2e");
    vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);

    await expect(loadEnv()).rejects.toThrow();
  });

  it("VERCEL_ENV=preview에서 APP_ENV=e2e는 허용한다", async () => {
    vi.stubEnv("VERCEL_ENV", "preview");
    vi.stubEnv("APP_ENV", "e2e");
    vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);

    await expect(loadEnv()).resolves.toMatchObject({ APP_ENV: "e2e" });
  });

  it("VERCEL_ENV가 없으면 APP_ENV=e2e를 허용한다(로컬, CI e2e)", async () => {
    vi.stubEnv("APP_ENV", "e2e");

    await expect(loadEnv()).resolves.toMatchObject({ APP_ENV: "e2e" });
  });

  it("운영(VERCEL_ENV=production)에서는 OAUTH_STATE_SECRET이 없으면 검증에 실패한다", async () => {
    vi.stubEnv("VERCEL_ENV", "production");
    vi.stubEnv("APP_ENV", "production");

    await expect(loadEnv()).rejects.toThrow();
  });

  it("운영(APP_ENV=production)에서는 OAUTH_STATE_SECRET이 없으면 검증에 실패한다", async () => {
    vi.stubEnv("APP_ENV", "production");

    await expect(loadEnv()).rejects.toThrow();
  });

  it("운영에서 OAUTH_STATE_SECRET을 설정하면 통과한다", async () => {
    vi.stubEnv("VERCEL_ENV", "production");
    vi.stubEnv("APP_ENV", "production");
    vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);

    await expect(loadEnv()).resolves.toMatchObject({
      OAUTH_STATE_SECRET: PRODUCTION_SECRET,
    });
  });

  it.each(["preview", "development"])(
    "Vercel 배포(VERCEL_ENV=%s)에서도 OAUTH_STATE_SECRET이 없으면 검증에 실패한다",
    async (vercelEnv) => {
      vi.stubEnv("VERCEL_ENV", vercelEnv);

      await expect(loadEnv()).rejects.toThrow();
    },
  );

  it("Vercel 미리보기 배포에서 OAUTH_STATE_SECRET을 설정하면 통과한다", async () => {
    vi.stubEnv("VERCEL_ENV", "preview");
    vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);

    await expect(loadEnv()).resolves.toMatchObject({ VERCEL_ENV: "preview" });
  });

  it("OAUTH_STATE_SECRET은 32자보다 짧으면 검증에 실패한다", async () => {
    vi.stubEnv("OAUTH_STATE_SECRET", "too-short");

    await expect(loadEnv()).rejects.toThrow();
  });

  it("개발 환경에서는 OAUTH_STATE_SECRET 없이 통과한다", async () => {
    await expect(loadEnv()).resolves.toMatchObject({ APP_ENV: "development" });
  });
});
