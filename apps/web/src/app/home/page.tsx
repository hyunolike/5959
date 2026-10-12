"use client";

import Link from "next/link";
import { PencilLine } from "lucide-react";
import { Suspense } from "react";

import { useMeQuery } from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { stopNotificationStream } from "@/features/notification-stream";
import { HomeInstall } from "@/features/install-app";
import { Button, Spinner } from "@/shared/ui";
import { FeedList } from "@/widgets/feed-list";
import { BossBanner } from "@/widgets/raid-arena";

/**
 * 홈은 피드다(US2). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다
 * (온보딩 전이면 /onboarding으로 보낸다).
 */
export default function HomePage() {
  const { data: member, isError } = useMeQuery();

  return (
    <div className="flex w-full max-w-[720px] flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-neutral-900">마음 이야기</h1>
          {member?.nickname ? (
            <p className="mt-1 text-xs text-neutral-500">
              {member.nickname}님, 반가워요
            </p>
          ) : null}
          {isError ? (
            <p role="alert" className="text-sm text-red-600">
              프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
            </p>
          ) : null}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          <LogoutButton onLoggedOut={stopNotificationStream} />
          <Button asChild className="px-3">
            <Link href="/write">
              <PencilLine size={16} aria-hidden />
              고민 쓰기
            </Link>
          </Button>
        </div>
      </header>
      {/* 살아 있는 보스가 있으면 레이드로 가는 안내를 보인다(006 US5-AC7). */}
      <BossBanner />
      <HomeInstall />
      {/* 정렬과 필터를 주소 검색어에서 읽는다(useSearchParams). */}
      <Suspense
        fallback={
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        }
      >
        <FeedList />
      </Suspense>
    </div>
  );
}
