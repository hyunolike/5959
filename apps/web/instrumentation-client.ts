import * as Sentry from "@sentry/nextjs";

import { env } from "@/shared/config";
import { scrubBreadcrumb, scrubEvent } from "@/shared/lib";

/**
 * 브라우저 초기화. HTML 로드 뒤, React 하이드레이션 전에 실행된다.
 * `NEXT_PUBLIC_SENTRY_DSN`이 없으면(로컬/CI 기본값) 초기화하지 않고, 그러면
 * 어디에도 네트워크 요청이 나가지 않는다.
 */
if (env.NEXT_PUBLIC_SENTRY_DSN) {
  Sentry.init({
    dsn: env.NEXT_PUBLIC_SENTRY_DSN,
    // 무료 플랜 한도를 지키기 위해 오류만 수집하고 성능 추적은 끈다.
    tracesSampleRate: 0,
    beforeSend: (event) => scrubEvent(event) as unknown as Sentry.ErrorEvent,
    beforeBreadcrumb: (breadcrumb) =>
      scrubBreadcrumb(breadcrumb) as unknown as Sentry.Breadcrumb,
  });
}
