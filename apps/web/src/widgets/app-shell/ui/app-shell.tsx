"use client";

import { House, PencilLine, Swords, UserRound } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { cn } from "@/shared/lib";
import { BrandMark } from "@/shared/ui";

const NAVIGATION = [
  { href: "/home", label: "마음 이야기", icon: House },
  { href: "/raid", label: "함께 이겨내기", icon: Swords },
  { href: "/write", label: "고민 쓰기", icon: PencilLine },
  { href: "/my", label: "내 마음 기록", icon: UserRound },
];

export function AppShell({
  children,
  authenticated,
  notification,
}: {
  children: ReactNode;
  authenticated: boolean;
  notification?: ReactNode;
}) {
  const pathname = usePathname();
  const isActive = (href: string) =>
    pathname === href || pathname.startsWith(`${href}/`);
  return (
    <>
      <a
        href="#main-content"
        className="text-brand-700 sr-only z-50 rounded-xl bg-white px-5 py-3 focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        본문으로 건너뛰기
      </a>
      <header className="sticky top-0 isolate z-30 border-b border-neutral-200/70">
        <div aria-hidden className="absolute inset-0 -z-10 bg-white/95" />
        <div className="mx-auto flex h-16 max-w-[1120px] items-center justify-between px-5 sm:h-18 sm:px-8">
          <Link
            href={authenticated ? "/home" : "/login"}
            aria-label="오구오구 홈"
          >
            <BrandMark />
          </Link>
          <div className="flex items-center gap-3">
            {authenticated ? (
              notification
            ) : (
              <span className="hidden text-sm text-neutral-500 sm:block">
                마음을 나누는 오구오구
              </span>
            )}
          </div>
        </div>
      </header>
      <div
        className={cn(
          "mx-auto flex w-full max-w-[1120px] flex-1 gap-12 px-5 sm:px-8",
          authenticated ? "py-7 pb-28 md:py-10" : "py-8 sm:py-14",
        )}
      >
        {authenticated ? (
          <aside className="sticky top-28 hidden h-fit w-44 shrink-0 md:block">
            <nav aria-label="주요 메뉴" className="flex flex-col gap-1">
              {NAVIGATION.map(({ href, label, icon: Icon }) => (
                <Link
                  key={href}
                  href={href}
                  aria-current={isActive(href) ? "page" : undefined}
                  className={cn(
                    "flex min-h-12 items-center gap-3 rounded-lg px-3 text-sm font-medium transition-colors",
                    isActive(href)
                      ? "bg-brand-50 text-brand-700"
                      : "text-neutral-600 hover:bg-neutral-100",
                  )}
                >
                  <Icon size={20} aria-hidden strokeWidth={1.8} />
                  {label}
                </Link>
              ))}
            </nav>
          </aside>
        ) : null}
        <main
          id="main-content"
          tabIndex={-1}
          className="flex min-w-0 flex-1 flex-col items-center"
        >
          {children}
        </main>
      </div>
      {authenticated ? (
        <nav
          aria-label="모바일 주요 메뉴"
          className="fixed right-0 bottom-0 left-0 z-30 flex border-t border-neutral-200 bg-white px-2 pt-2 pb-[max(10px,env(safe-area-inset-bottom))] md:hidden"
        >
          {NAVIGATION.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              aria-current={isActive(href) ? "page" : undefined}
              className={cn(
                "flex min-h-14 flex-1 flex-col items-center justify-center gap-1 rounded-lg text-[11px] font-medium",
                isActive(href) ? "text-brand-700" : "text-neutral-500",
              )}
            >
              <Icon size={21} aria-hidden strokeWidth={1.8} />
              {label}
            </Link>
          ))}
        </nav>
      ) : (
        <footer className="pb-6 text-center text-xs text-neutral-500">
          오구오구
        </footer>
      )}
    </>
  );
}
