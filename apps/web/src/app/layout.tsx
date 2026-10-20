import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";

import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "@/core/styles/globals.css";
import { UmamiAnalytics } from "@/core/analytics";
import { AppProviders } from "@/core/providers/app-providers";
import { ServiceWorkerRegistration } from "@/core/pwa";
import { env } from "@/shared/config";
import { ONBOARDED_COOKIE, REFRESH_TOKEN_COOKIE } from "@/shared/server";
import { AppShell } from "@/widgets/app-shell";
import { NotificationBell } from "@/widgets/notification-bell";

import { rootMetadata } from "./root-metadata";

/** 서비스 주소는 배포마다 다르므로 요청을 받을 때 환경 변수에서 읽는다. 내용은 `root-metadata.ts`에 있다. */
export function generateMetadata(): Metadata {
  return rootMetadata(env.APP_ORIGIN);
}

export const viewport: Viewport = {
  width: "device-width",
  initialScale: 1,
  viewportFit: "cover",
  themeColor: "#ffffff",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // 알림 종은 온보딩을 마친 회원에게만 그린다(US1-AC8, research R14). 로그인하지 않았거나
  // 온보딩 전이면 위젯이 없으므로 티켓 요청도 스트림 연결도 나가지 않는다.
  const cookieStore = await cookies();
  const authenticated =
    cookieStore.has(REFRESH_TOKEN_COOKIE) &&
    cookieStore.get(ONBOARDED_COOKIE)?.value === "1";

  return (
    <html lang="ko" className="h-full antialiased">
      <body className="flex min-h-full flex-col">
        <AppProviders>
          <AppShell
            authenticated={authenticated}
            notification={authenticated ? <NotificationBell /> : undefined}
          >
            {children}
          </AppShell>
        </AppProviders>
        <UmamiAnalytics appOrigin={env.APP_ORIGIN} />
        <ServiceWorkerRegistration appOrigin={env.APP_ORIGIN} />
      </body>
    </html>
  );
}
