import type { Metadata, Viewport } from "next";
import { cookies } from "next/headers";

import "pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css";
import "@/core/styles/globals.css";
import { AppProviders } from "@/core/providers/app-providers";
import { ONBOARDED_COOKIE, REFRESH_TOKEN_COOKIE } from "@/shared/server";
import { AppShell } from "@/widgets/app-shell";
import { NotificationBell } from "@/widgets/notification-bell";

export const metadata: Metadata = {
  title: { default: "오구오구", template: "%s | 오구오구" },
  description: "감정을 나누고 함께 이겨내는 서비스",
  appleWebApp: { capable: true, title: "오구오구", statusBarStyle: "default" },
  icons: { icon: "/icon.png", apple: "/brand/apple-touch-icon.png" },
};

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
      </body>
    </html>
  );
}
