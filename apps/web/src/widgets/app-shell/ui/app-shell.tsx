"use client";

import {
  Cards,
  House,
  PencilSimpleLine,
  Sword,
  User,
} from "@phosphor-icons/react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import { cn } from "@/shared/lib";
import { BrandMark } from "@/shared/ui";
import {
  GuestAccessProvider,
  useGuestAccess,
} from "@/features/auth/guest-access";
import { NavigationItem } from "./navigation-item";

const NAVIGATION = [
  { href: "/home", label: "마음 이야기", shortLabel: "이야기", icon: House },
  { href: "/raid", label: "함께 이겨내기", shortLabel: "함께", icon: Sword },
  {
    href: "/write",
    label: "고민 쓰기",
    shortLabel: "고민 쓰기",
    icon: PencilSimpleLine,
    primary: true,
  },
  // 좁은 화면에서는 다섯 칸이라 고민 쓰기가 정가운데 칸에 온다(010 US5-AC1).
  { href: "/cards", label: "몬스터 도감", shortLabel: "도감", icon: Cards },
  { href: "/my", label: "내 마음 기록", shortLabel: "기록", icon: User },
];

/** 글 상세의 주소. 글 고치기(`/post/1/edit`)는 들지 않는다. */
const POST_DETAIL = /^\/post\/\d+$/;

export function AppShell({
  children,
  authenticated,
  notification,
}: {
  children: ReactNode;
  authenticated: boolean;
  notification?: ReactNode;
}) {
  return (
    <GuestAccessProvider authenticated={authenticated}>
      <ShellContents authenticated={authenticated} notification={notification}>
        {children}
      </ShellContents>
    </GuestAccessProvider>
  );
}

function ShellContents({
  children,
  authenticated,
  notification,
}: {
  children: ReactNode;
  authenticated: boolean;
  notification?: ReactNode;
}) {
  const pathname = usePathname();
  const { requestLogin } = useGuestAccess();
  const showNavigation = authenticated || pathname === "/home";
  // 글 상세는 좁은 화면에서 아래 자리를 댓글 입력줄이 쓴다. 하단 메뉴를 그리지 않는다.
  const showMobileNavigation = showNavigation && !POST_DETAIL.test(pathname);
  const guestNavigation = (href: string) =>
    !authenticated && href !== "/home"
      ? {
          prefetch: false,
          onClick: (event: React.MouseEvent<HTMLAnchorElement>) => {
            event.preventDefault();
            requestLogin(href);
          },
        }
      : {};
  const current = (href: string): "page" | "location" | undefined =>
    pathname === href
      ? "page"
      : pathname.startsWith(`${href}/`)
        ? "location"
        : undefined;
  return (
    <>
      <a
        href="#main-content"
        className="text-fg rounded-control bg-surface sr-only z-50 px-5 py-3 focus:not-sr-only focus:fixed focus:top-3 focus:left-3"
      >
        본문으로 건너뛰기
      </a>
      <header className="border-line sticky top-0 isolate z-30 border-b">
        <div aria-hidden className="bg-surface/95 absolute inset-0 -z-10" />
        <div className="mx-auto flex h-14 max-w-[1120px] items-center justify-between px-5 sm:h-18 sm:px-8">
          <Link href={showNavigation ? "/home" : "/"} aria-label="오구오구 홈">
            <BrandMark />
          </Link>
          <div className="flex items-center gap-3">
            {authenticated ? (
              notification
            ) : showNavigation ? (
              <Link
                href="/login"
                className="text-body-sm text-fg inline-flex min-h-11 items-center font-bold"
              >
                로그인
              </Link>
            ) : (
              <span className="text-body-sm text-fg-subtle hidden sm:block">
                마음을 나누는 오구오구
              </span>
            )}
          </div>
        </div>
      </header>
      {/*
        좁은 화면의 메뉴는 화면 아래에 붙어 있지만 DOM에서는 본문보다 앞에 둔다. 뒤에 두면 Tab으로는
        끝없이 늘어나는 피드를 다 지나야 메뉴에 닿는다.
      */}
      {showMobileNavigation ? (
        <nav
          aria-label="모바일 주요 메뉴"
          className="border-line bg-surface rounded-t-surface fixed right-0 bottom-0 left-0 z-30 grid grid-cols-5 border border-b-0 px-3 pt-2 pb-[max(8px,env(safe-area-inset-bottom))] md:hidden"
        >
          {NAVIGATION.map((item) => (
            <NavigationItem
              key={item.href}
              {...item}
              current={current(item.href)}
              mobile
              locked={!authenticated && item.href !== "/home"}
              {...guestNavigation(item.href)}
            />
          ))}
        </nav>
      ) : null}
      <div
        className={cn(
          "mx-auto flex w-full max-w-[1120px] flex-1 gap-12 px-5 sm:px-8",
          showNavigation
            ? // 아래 여백은 하단 메뉴(104px)와 글 상세의 댓글 입력줄 가운데 높은 쪽이다.
              "py-6 pb-[max(calc(104px+env(safe-area-inset-bottom)),calc(var(--comment-dock-height,0px)+24px))] md:py-10"
            : "py-8 sm:py-14",
        )}
      >
        {showNavigation ? (
          <aside className="sticky top-28 hidden h-fit w-44 shrink-0 md:block">
            <nav aria-label="주요 메뉴" className="flex flex-col gap-1">
              {NAVIGATION.map((item) => (
                <NavigationItem
                  key={item.href}
                  {...item}
                  current={current(item.href)}
                  locked={!authenticated && item.href !== "/home"}
                  {...guestNavigation(item.href)}
                />
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
      {showNavigation ? null : (
        <footer className="text-caption text-fg-subtle pb-6 text-center">
          오구오구
        </footer>
      )}
    </>
  );
}
