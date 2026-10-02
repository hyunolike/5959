import * as Sentry from "@sentry/nextjs";

import { env } from "@/shared/config";
import { scrubBreadcrumb, scrubEvent } from "@/shared/lib";

/**
 * Node.js 런타임(Route Handler, Server Component 등) 초기화.
 * `NEXT_PUBLIC_SENTRY_DSN`이 없으면(로컬/CI 기본값) 초기화하지 않고, 그러면
 * 어디에도 네트워크 요청이 나가지 않는다.
 */
if (env.NEXT_PUBLIC_SENTRY_DSN) {
  Sentry.init({
    dsn: env.NEXT_PUBLIC_SENTRY_DSN,
    // 무료 플랜 한도를 지키기 위해 오류만 수집하고 성능 추적은 끈다.
    tracesSampleRate: 0,
    // SDK가 기본으로 모으는 사용자 정보/쿠키/헤더/요청 본문/쿼리 파라미터를
    // 전부 끈다(과거 SDK의 `sendDefaultPii: false`에 해당 — v11부터는
    // `dataCollection`으로 바뀌었고, 각 필드 기본값이 전부 `true`다).
    dataCollection: {
      userInfo: false,
      cookies: false,
      httpHeaders: false,
      httpBodies: [],
      urlQueryParams: false,
      stackFrameVariables: false,
      frameContextLines: 0,
    },
    beforeSend: (event) => scrubEvent(event) as unknown as Sentry.ErrorEvent,
    beforeBreadcrumb: (breadcrumb) =>
      scrubBreadcrumb(breadcrumb) as unknown as Sentry.Breadcrumb,
  });
}
