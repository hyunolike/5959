import { createEnv } from "@t3-oss/env-nextjs";
import { z } from "zod";

/**
 * Type-safe environment variables, validated at build/boot time.
 * Add new variables here instead of reading `process.env` directly elsewhere.
 */
export const env = createEnv({
  server: {
    NODE_ENV: z
      .enum(["development", "test", "production"])
      .default("development"),
    // BFF 라우트만 읽는다. 브라우저는 API 도메인을 직접 호출하지 않는다.
    API_ORIGIN: z.string().url().default("http://localhost:8080"),
    // API 호출 시 보내는 비밀 키. API 쪽 OGU_BFF_KEY와 값이 같아야 한다.
    BFF_API_KEY: z.string().min(1).default("local-bff-key"),
    // 이 웹 서비스의 출처(Origin 검사, OAuth redirect_uri 구성에 쓴다).
    APP_ORIGIN: z.string().url().default("http://localhost:3000"),
    KAKAO_CLIENT_ID: z.string().min(1).optional(),
    GOOGLE_CLIENT_ID: z.string().min(1).optional(),
    // 개발/스테이징에서 에러 프로브(테스트용 강제 에러 라우트)를 켤지 여부.
    ENABLE_ERROR_PROBE: z.stringbool().optional(),
    // e2e면 OAuth 시작 라우트가 제공자 대신 자기 콜백으로 바로 보낸다(가짜 제공자).
    APP_ENV: z
      .enum(["development", "e2e", "production"])
      .default("development"),
    // Vercel이 배포 환경마다 넣어 주는 값. 로컬과 CI에서는 없다.
    VERCEL_ENV: z.enum(["production", "preview", "development"]).optional(),
    // `__Host-ogu_oauth` 쿠키 서명(HMAC) 키. 운영에서는 반드시 설정해야 하고,
    // 그 밖에서는 없으면 shared/server/oauth-secret.ts의 개발용 값을 쓴다.
    OAUTH_STATE_SECRET: z.string().min(32).optional(),
  },
  client: {
    NEXT_PUBLIC_SENTRY_DSN: z.string().min(1).optional(),
  },
  experimental__runtimeEnv: {
    NEXT_PUBLIC_SENTRY_DSN: process.env.NEXT_PUBLIC_SENTRY_DSN,
  },
  // 변수 사이의 조합 규칙. 서버에서만 검사한다(브라우저 번들에는 서버 변수가 없다).
  createFinalSchema: (shape, isServer) =>
    z.object(shape).superRefine((values, ctx) => {
      if (!isServer) {
        return;
      }
      const isProduction =
        values.VERCEL_ENV === "production" || values.APP_ENV === "production";
      if (values.VERCEL_ENV === "production" && values.APP_ENV === "e2e") {
        ctx.addIssue({
          code: "custom",
          path: ["APP_ENV"],
          message:
            "운영 배포(VERCEL_ENV=production)에서는 APP_ENV=e2e(가짜 OAuth 제공자)를 쓸 수 없다",
        });
      }
      if (isProduction && values.OAUTH_STATE_SECRET === undefined) {
        ctx.addIssue({
          code: "custom",
          path: ["OAUTH_STATE_SECRET"],
          message: "운영에서는 OAUTH_STATE_SECRET을 설정해야 한다",
        });
      }
    }),
});
