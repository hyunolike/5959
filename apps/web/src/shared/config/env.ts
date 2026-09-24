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
  },
  client: {},
  experimental__runtimeEnv: {},
});
