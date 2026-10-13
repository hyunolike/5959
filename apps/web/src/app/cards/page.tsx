import type { Metadata } from "next";

import { MonsterCollection } from "@/widgets/monster-collection";

export const metadata: Metadata = { title: "몬스터 도감" };

/** 몬스터 도감(010). 로그인과 온보딩은 proxy.ts의 라우트 가드가 먼저 확인한다(US1-AC8). */
export default function CardsPage() {
  return (
    <div className="flex w-full max-w-xl flex-col gap-6">
      <header>
        <h1 className="text-heading text-fg font-bold">몬스터 도감</h1>
        <p className="text-body-sm text-fg-subtle mt-1 break-keep">
          함께 물리친 몬스터가 카드로 모여요.
        </p>
      </header>
      <MonsterCollection />
    </div>
  );
}
