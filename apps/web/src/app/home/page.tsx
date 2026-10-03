"use client";

import Link from "next/link";

import { useMeQuery } from "@/entities/member";
import { LogoutButton } from "@/features/auth/logout";
import { Button, Card, Spinner } from "@/shared/ui";

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
      <div className="mt-4">
        <Button asChild>
          <Link href="/write">고민 쓰기</Link>
        </Button>
      </div>
      <div className="mt-6">
        <LogoutButton />
      </div>
    </Card>
  );
}
