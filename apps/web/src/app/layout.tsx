import type { Metadata } from "next";
import { Geist, Geist_Mono } from "next/font/google";

import "@/core/styles/globals.css";
import { AppProviders } from "@/core/providers/app-providers";

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

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html
      lang="ko"
      className={`${geistSans.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col bg-neutral-50">
        <AppProviders>
          <main className="flex flex-1 flex-col items-center px-6 py-12">
            {children}
          </main>
        </AppProviders>
      </body>
    </html>
  );
}
