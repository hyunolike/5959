"use client";

import { useMeQuery } from "@/entities/member";
import { Card, Spinner } from "@/shared/ui";

export default function HomePage() {
  const { data: member, isPending } = useMeQuery();

  return (
    <Card className="w-full max-w-xl text-center">
      {isPending ? (
        <Spinner />
      ) : (
        <h1 className="text-2xl font-semibold text-neutral-900">
          {member?.nickname}님, 반가워요
        </h1>
      )}
      <p className="mt-4 text-sm text-neutral-500">
        고민 쓰기는 준비 중이에요.
      </p>
    </Card>
  );
}
