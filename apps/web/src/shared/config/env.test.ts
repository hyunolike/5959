// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// env.ts는 불러오는 순간 process.env를 검증한다. 빌드(next build)와 기동도 이
// 모듈을 불러오므로, 여기서 예외가 나면 빌드와 기동이 실패한다.
async function loadEnv() {
  vi.resetModules();
  return (await import("./env")).env;
}

const PRODUCTION_SECRET = "prod-oauth-state-secret-0123456789abcdefgh";

/** Vercel 배포에서 명시적으로 넣어야 하는 값(기본값이면 검증에 실패한다). */
function stubExplicitVercelValues() {
  vi.stubEnv("API_ORIGIN", "https://api.ogu.example.com");
  vi.stubEnv("BFF_API_KEY", "vercel-bff-key");
  vi.stubEnv("APP_ORIGIN", "https://ogu.example.com");
}

beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  for (const name of [
    "VERCEL_ENV",
    "APP_ENV",
    "OAUTH_STATE_SECRET",
    "API_ORIGIN",
    "BFF_API_KEY",
    "APP_ORIGIN",
  ]) {
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
    stubExplicitVercelValues();

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
    stubExplicitVercelValues();

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
    stubExplicitVercelValues();

    await expect(loadEnv()).resolves.toMatchObject({ VERCEL_ENV: "preview" });
  });

  it("OAUTH_STATE_SECRET은 32자보다 짧으면 검증에 실패한다", async () => {
    vi.stubEnv("OAUTH_STATE_SECRET", "too-short");

    await expect(loadEnv()).rejects.toThrow();
  });

  it("개발 환경에서는 OAUTH_STATE_SECRET 없이 통과한다", async () => {
    await expect(loadEnv()).resolves.toMatchObject({ APP_ENV: "development" });
  });

  describe("Vercel 배포는 기본값으로 조용히 넘어가면 안 된다", () => {
    it.each(["API_ORIGIN", "BFF_API_KEY", "APP_ORIGIN"] as const)(
      "VERCEL_ENV가 있는데 %s를 안 주면(기본값을 쓰게 되면) 검증에 실패한다",
      async (missing) => {
        vi.stubEnv("VERCEL_ENV", "production");
        vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);
        stubExplicitVercelValues();
        vi.stubEnv(missing, undefined);

        await expect(loadEnv()).rejects.toThrow();
      },
    );

    it("VERCEL_ENV가 있는데 BFF_API_KEY가 로컬 개발용 값(local-bff-key)이면 명시적으로 넣었어도 검증에 실패한다", async () => {
      vi.stubEnv("VERCEL_ENV", "preview");
      vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);
      stubExplicitVercelValues();
      vi.stubEnv("BFF_API_KEY", "local-bff-key");

      await expect(loadEnv()).rejects.toThrow();
    });

    it("VERCEL_ENV가 있어도 API_ORIGIN, BFF_API_KEY, APP_ORIGIN을 모두 명시하면 통과한다", async () => {
      vi.stubEnv("VERCEL_ENV", "production");
      vi.stubEnv("OAUTH_STATE_SECRET", PRODUCTION_SECRET);
      stubExplicitVercelValues();

      await expect(loadEnv()).resolves.toMatchObject({
        API_ORIGIN: "https://api.ogu.example.com",
        BFF_API_KEY: "vercel-bff-key",
        APP_ORIGIN: "https://ogu.example.com",
      });
    });

    it("VERCEL_ENV가 없으면(로컬) API_ORIGIN 등이 없어도 기본값으로 통과한다", async () => {
      await expect(loadEnv()).resolves.toMatchObject({
        API_ORIGIN: "http://localhost:8080",
        BFF_API_KEY: "local-bff-key",
        APP_ORIGIN: "http://localhost:3000",
      });
    });
  });

  it("빈 문자열인 선택 값은 없는 것으로 처리한다(cp .env.example .env.local 해도 min() 검증에 걸리지 않는다)", async () => {
    vi.stubEnv("KAKAO_CLIENT_ID", "");
    vi.stubEnv("OAUTH_STATE_SECRET", "");

    const env = await loadEnv();

    expect(env.KAKAO_CLIENT_ID).toBeUndefined();
    expect(env.OAUTH_STATE_SECRET).toBeUndefined();
  });
});
