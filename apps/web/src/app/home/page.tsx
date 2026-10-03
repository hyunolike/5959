"use client";

import Link from "next/link";
import { Suspense } from "react";

import { useMeQuery } from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { Button, Spinner } from "@/shared/ui";
import { FeedList } from "@/widgets/feed-list";

/**
 * 홈은 피드다(US2). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다
 * (온보딩 전이면 /onboarding으로 보낸다).
 */
export default function HomePage() {
  const { data: member } = useMeQuery();

  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex items-center justify-between gap-4">
        <h1 className="text-xl font-semibold text-neutral-900">
          {member?.nickname ? `${member.nickname}님, 반가워요` : "피드"}
        </h1>
        <div className="flex items-center gap-2">
          <Button asChild>
            <Link href="/write">고민 쓰기</Link>
          </Button>
          <LogoutButton />
        </div>
      </header>
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
