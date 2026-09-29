import * as Sentry from "@sentry/nextjs";

/**
 * Next.js가 서버 인스턴스를 시작할 때 한 번 부른다. 실제 `Sentry.init` 호출은
 * 런타임별 설정 파일(`sentry.server.config.ts`, `sentry.edge.config.ts`)에
 * 있다 — 두 파일 모두 `NEXT_PUBLIC_SENTRY_DSN`이 없으면 아무것도 하지 않는다.
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME === "edge") {
    await import("./sentry.edge.config");
  } else {
    await import("./sentry.server.config");
  }
}

/** 서버에서 잡힌 오류를 Sentry로 보낸다(DSN이 없으면 조용히 아무 일도 안 한다). */
export const onRequestError = Sentry.captureRequestError;
