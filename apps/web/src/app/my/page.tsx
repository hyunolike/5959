"use client";

import Link from "next/link";
import { Suspense } from "react";

import {
  CAREER_YEAR_LABELS,
  JOB_ROLE_LABELS,
  useMeQuery,
} from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { stopNotificationStream } from "@/features/notification-stream";
import { Button, Card, Spinner } from "@/shared/ui";
import { EmotionStatsPanel } from "@/widgets/emotion-stats-panel";
import { MyActivity } from "@/widgets/my-activity";

/**
 * 마이페이지: 내 프로필(FR-015), 감정 통계(004 US4), 내 활동 탭(004 US3). 로그인과 온보딩은 proxy.ts의 라우트 가드가
 * 먼저 확인한다.
 */
export default function MyPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header className="flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-40">
          <p className="text-label text-fg-subtle mb-1 font-bold">
            내 마음 기록
          </p>
          <h1 className="text-heading text-fg font-bold">마이페이지</h1>
        </div>
        <div className="ml-auto flex items-center gap-2">
          <Button asChild variant="ghost" size="sm">
            <Link href="/home">피드로</Link>
          </Button>
          <LogoutButton onLoggedOut={stopNotificationStream} />
        </div>
      </header>
      <Profile />
      <EmotionStatsPanel />
      {/* 고른 탭을 주소 검색어에서 읽는다(useSearchParams). */}
      <Suspense
        fallback={
          <div className="flex justify-center py-8">
            <Spinner />
          </div>
        }
      >
        <MyActivity />
      </Suspense>
    </div>
  );
}

function Profile() {
  const { data: member, isPending, isError } = useMeQuery();

  if (isPending) {
    return (
      <Card className="flex w-full items-center justify-center">
        <Spinner />
      </Card>
    );
  }

  if (isError || !member) {
    return (
      <Card className="w-full">
        <p role="alert" className="text-body-sm text-danger">
          프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }

  return (
    <Card className="flex w-full flex-col gap-4">
      <div className="border-line flex flex-wrap items-center justify-between gap-2 border-b pb-4">
        <p className="text-body text-fg min-w-0 font-bold break-words">
          {member.nickname}님의 마음 기록
        </p>
        <Button asChild variant="secondary" size="sm" className="ml-auto">
          <Link href="/my/edit">프로필 수정</Link>
        </Button>
      </div>
      <dl className="text-body-sm flex flex-col gap-3">
        <div className="flex items-center justify-between gap-4">
          <dt className="text-fg-subtle shrink-0">닉네임</dt>
          <dd className="text-fg min-w-0 font-medium break-words">
            {member.nickname}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt className="text-fg-subtle shrink-0">직군</dt>
          <dd className="text-fg font-medium">
            {member.jobRole ? JOB_ROLE_LABELS[member.jobRole] : "-"}
          </dd>
        </div>
        <div className="flex items-center justify-between gap-4">
          <dt className="text-fg-subtle shrink-0">경력</dt>
          <dd className="text-fg font-medium">
            {member.careerYear ? CAREER_YEAR_LABELS[member.careerYear] : "-"}
          </dd>
        </div>
      </dl>
    </Card>
  );
}
