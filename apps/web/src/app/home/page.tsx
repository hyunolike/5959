"use client";

import { useMeQuery } from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { Card, Spinner } from "@/shared/ui";

export default function HomePage() {
  const { data: member, isPending, isError } = useMeQuery();

  return (
    <Card className="w-full max-w-xl text-center">
      {isPending ? (
        <Spinner />
      ) : isError ? (
        <p role="alert" className="text-sm text-red-600">
          프로필을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      ) : (
        <h1 className="text-2xl font-semibold text-neutral-900">
          {member?.nickname}님, 반가워요
        </h1>
      )}
      <p className="mt-4 text-sm text-neutral-500">
        고민 쓰기는 준비 중이에요.
      </p>
      <div className="mt-6">
        <LogoutButton />
      </div>
    </Card>
  );
}
