"use client";

import {
  Heart,
  House,
  PencilLine,
  Sprout,
  Swords,
  UserRound,
} from "lucide-react";
import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { cn } from "@/shared/lib";
import { BrandMark, Button } from "@/shared/ui";

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
        <div
          aria-hidden
          className="absolute inset-0 -z-10 bg-white/95 backdrop-blur-md"
        />
        <div className="mx-auto flex h-20 max-w-[1200px] items-center justify-between px-5 sm:px-8">
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
                나의 속도로, 함께.
              </span>
            )}
            {authenticated ? (
              <Button asChild size="sm" className="hidden sm:inline-flex">
                <Link href="/write">
                  <PencilLine size={16} aria-hidden />
                  고민 쓰기
                </Link>
              </Button>
            ) : null}
          </div>
        </div>
      </header>
      <div
        className={cn(
          "mx-auto flex w-full max-w-[1200px] flex-1 gap-9 px-5 sm:px-8",
          authenticated ? "py-8 pb-28 md:pb-10" : "py-6 sm:py-16",
        )}
      >
        {authenticated ? (
          <aside className="sticky top-28 hidden h-fit w-48 shrink-0 md:block">
            <p className="mb-5 px-3 text-xs font-medium text-neutral-500">
              마음을 나누는 공간
            </p>
            <nav aria-label="주요 메뉴" className="flex flex-col gap-2">
              {NAVIGATION.map(({ href, label, icon: Icon }) => (
                <Link
                  key={href}
                  href={href}
                  aria-current={isActive(href) ? "page" : undefined}
                  className={cn(
                    "flex min-h-12 items-center gap-3 rounded-xl px-4 text-sm font-medium transition-colors",
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
            <div className="mt-8 rounded-2xl bg-[#edf5f0] p-5 text-sm text-[#466153]">
              <Sprout aria-hidden size={22} className="mb-3" />
              <p className="font-bold">어떤 마음이든 괜찮아요.</p>
              <p className="mt-2 text-xs leading-6">
                정답을 찾기보다 서로의 이야기에 귀 기울여 주세요.
              </p>
            </div>
          </aside>
        ) : null}
        <main
          id="main-content"
          className="flex min-w-0 flex-1 flex-col items-center"
        >
          {children}
        </main>
        {authenticated && pathname === "/home" ? (
          <aside className="sticky top-28 hidden h-fit w-56 shrink-0 xl:block">
            <div className="border-brand-100 bg-brand-50 overflow-hidden rounded-3xl border px-5 pt-6 pb-5">
              <Heart aria-hidden size={20} className="text-brand-600 mb-4" />
              <h2 className="text-lg font-bold text-neutral-900">
                작은 공감이
                <br />큰 힘이 될 거예요.
              </h2>
              <Image
                src="/brand/ogu-companion.png"
                alt="작은 감정 몬스터를 안아주는 오구"
                width={224}
                height={200}
                className="my-4 h-40 w-full object-contain"
              />
              <p className="text-xs leading-6 text-neutral-600">
                같은 마음을 가진 사람에게
                <br />
                따뜻한 한마디를 건네보세요.
              </p>
            </div>
          </aside>
        ) : null}
      </div>
      {authenticated ? (
        <nav
          aria-label="모바일 주요 메뉴"
          className="fixed right-0 bottom-0 left-0 z-30 flex border-t border-neutral-200 bg-white/95 px-2 pt-2 pb-[max(10px,env(safe-area-inset-bottom))] backdrop-blur-md md:hidden"
        >
          {NAVIGATION.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              aria-current={isActive(href) ? "page" : undefined}
              className={cn(
                "flex min-h-14 flex-1 flex-col items-center justify-center gap-1 rounded-xl text-[10px] font-medium",
                isActive(href) ? "text-brand-700" : "text-neutral-500",
              )}
            >
              <Icon size={21} aria-hidden strokeWidth={1.8} />
              {label}
            </Link>
          ))}
        </nav>
      ) : (
        <footer className="pb-8 text-center text-xs text-neutral-500">
          마음을 나누고, 조금 더 가벼운 내일로.
        </footer>
      )}
    </>
  );
}
