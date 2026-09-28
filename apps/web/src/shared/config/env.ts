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
    // T056에서 e2e용 가짜 OAuth 분기와 production 조합 검증에 쓴다.
    APP_ENV: z
      .enum(["development", "e2e", "production"])
      .default("development"),
  },
  client: {
    NEXT_PUBLIC_SENTRY_DSN: z.string().min(1).optional(),
  },
  experimental__runtimeEnv: {
    NEXT_PUBLIC_SENTRY_DSN: process.env.NEXT_PUBLIC_SENTRY_DSN,
  },
});
