import { createEnv } from "@t3-oss/env-nextjs";
import { z } from "zod";

/**
 * Type-safe environment variables, validated at build/boot time.
 * Add new variables here instead of reading `process.env` directly elsewhere.
 */
export const env = createEnv({
  // .env.example을 그대로 .env.local로 복사하면 선택 값(KAKAO_CLIENT_ID 등)이
  // 빈 문자열로 남는다. 빈 문자열은 없는 값과 같이 취급해 min() 검증에 걸려
  // `pnpm dev`가 죽는 일을 막는다.
  emptyStringAsUndefined: true,
  server: {
    NODE_ENV: z
      .enum(["development", "test", "production"])
      .default("development"),
    // BFF 라우트만 읽는다. 브라우저는 API 도메인을 직접 호출하지 않는다.
    API_ORIGIN: z.string().url().default("http://localhost:8080"),
    // 브라우저가 실시간 알림 스트림에 바로 붙을 API 출처(004 research R3). 없으면 API_ORIGIN을 쓴다.
    // BFF 티켓 라우트만 읽어 응답의 streamUrl로 내보낸다.
    SSE_PUBLIC_ORIGIN: z.string().url().optional(),
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
    // `__Host-ogu_oauth` 쿠키 서명(HMAC) 키. Vercel 배포(미리보기 포함)와 운영에서는 반드시 설정해야 하고,
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
      // Vercel에 올라간 배포(운영, 미리보기 모두)는 공개 주소라 개발용 서명 키를 쓰면 안 된다.
      const needsOAuthStateSecret =
        values.VERCEL_ENV !== undefined || values.APP_ENV === "production";
      if (values.VERCEL_ENV === "production" && values.APP_ENV === "e2e") {
        ctx.addIssue({
          code: "custom",
          path: ["APP_ENV"],
          message:
            "운영 배포(VERCEL_ENV=production)에서는 APP_ENV=e2e(가짜 OAuth 제공자)를 쓸 수 없다",
        });
      }
      if (needsOAuthStateSecret && values.OAUTH_STATE_SECRET === undefined) {
        ctx.addIssue({
          code: "custom",
          path: ["OAUTH_STATE_SECRET"],
          message:
            "Vercel 배포(VERCEL_ENV가 있을 때)와 운영에서는 OAUTH_STATE_SECRET을 설정해야 한다",
        });
      }
      // Vercel에 올라간 배포는 API_ORIGIN, BFF_API_KEY, APP_ORIGIN을 명시적으로
      // 넣어야 한다. 셋 다 기본값이 있어서, 빠뜨려도 조용히 로컬 값(localhost,
      // local-bff-key)으로 기동해 실제 API 대신 아무 데도 없는 주소를 부른다.
      // 여기서는 `values`(defaults 적용 뒤)가 아니라 `process.env`(원본)를 봐서
      // "정말로 안 넣었는지"를 구분한다.
      if (values.VERCEL_ENV !== undefined) {
        for (const key of [
          "API_ORIGIN",
          "BFF_API_KEY",
          "APP_ORIGIN",
        ] as const) {
          if (!process.env[key]) {
            ctx.addIssue({
              code: "custom",
              path: [key],
              message: `Vercel 배포(VERCEL_ENV가 있을 때)에서는 ${key}를 기본값 없이 명시적으로 설정해야 한다`,
            });
          }
        }
        if (values.BFF_API_KEY === "local-bff-key") {
          ctx.addIssue({
            code: "custom",
            path: ["BFF_API_KEY"],
            message:
              "Vercel 배포(VERCEL_ENV가 있을 때)에서는 BFF_API_KEY에 로컬 개발용 값(local-bff-key)을 쓸 수 없다",
          });
        }
      }
    }),
});
