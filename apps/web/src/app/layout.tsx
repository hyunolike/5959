import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { cookies } from "next/headers";

import "@/core/styles/globals.css";
import { AppProviders } from "@/core/providers/app-providers";
import { ONBOARDED_COOKIE } from "@/shared/server";
import { NotificationBell } from "@/widgets/notification-bell";

const geistSans = Geist({
  variable: "--font-geist-sans",
  subsets: ["latin"],
});

const geistMono = Geist_Mono({
  variable: "--font-geist-mono",
  subsets: ["latin"],
});

export const metadata: Metadata = {
  title: "오구오구",
  description: "감정을 나누고 함께 이겨내는 서비스",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // 알림 종은 온보딩을 마친 회원에게만 그린다(US1-AC8, research R14). 로그인하지 않았거나
  // 온보딩 전이면 위젯이 없으므로 티켓 요청도 스트림 연결도 나가지 않는다.
  const onboarded = (await cookies()).has(ONBOARDED_COOKIE);

  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col bg-neutral-50">
        <AppProviders>
          {onboarded ? (
            <header className="flex justify-end px-6 pt-4">
              <NotificationBell />
            </header>
          ) : null}
          <main className="flex flex-1 flex-col items-center px-6 py-12">
            {children}
          </main>
        </AppProviders>
      </body>
    </html>
  );
}
