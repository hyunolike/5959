"use client";

import Link from "next/link";

import { EMOTION_LABELS, MonsterSprite } from "@/entities/monster";
import { useRaidQuery } from "@/entities/raid";

/**
 * 홈의 보스 안내(006 US5-AC7). 살아 있는 보스가 있을 때만 감정과 남은 HP를 보이고 레이드 화면으로 잇는다.
 * 보스가 없거나 끝났거나 불러오지 못했으면 아무것도 그리지 않는다. 홈은 피드가 먼저다.
 */
export function BossBanner() {
  const { data } = useRaidQuery();
  const boss = data?.boss;
  if (!boss || boss.status !== "ALIVE") {
    return null;
  }
  return (
    <Link
      href="/raid"
      aria-label={`레이드: ${EMOTION_LABELS[boss.emotion]} 보스, HP ${boss.hp}/${boss.maxHp}`}
      data-emotion={boss.emotion}
      className="rounded-control bg-emotion-tint flex items-center gap-3 px-4 py-3 transition-[filter] hover:brightness-[0.97]"
    >
      <span className="size-12 shrink-0">
        <MonsterSprite emotion={boss.emotion} stage="full" boss sizes="48px" />
      </span>
      <span className="flex min-w-0 flex-col">
        <span className="text-body-sm text-fg font-bold">
          {EMOTION_LABELS[boss.emotion]} 보스가 나타났어요
        </span>
        <span className="text-caption text-emotion-fg font-medium tabular-nums">
          HP {boss.hp}/{boss.maxHp} · {boss.participantCount}명이 함께하고
          있어요
        </span>
      </span>
      <span className="text-label text-fg ml-auto shrink-0 font-bold">
        함께 공격하기
      </span>
    </Link>
  );
}
