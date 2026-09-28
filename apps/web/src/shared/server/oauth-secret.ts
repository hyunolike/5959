import "server-only";

import { env } from "@/shared/config";

/**
 * 개발, e2e에서만 쓰는 `__Host-ogu_oauth` 서명 키. 운영에서는 env.ts 검증이
 * OAUTH_STATE_SECRET 없이는 빌드와 기동을 막으므로 이 값이 쓰이지 않는다.
 */
const DEVELOPMENT_OAUTH_STATE_SECRET =
  "local-oauth-state-secret-not-for-production";

export function oauthStateSecret(): string {
  return env.OAUTH_STATE_SECRET ?? DEVELOPMENT_OAUTH_STATE_SECRET;
}
