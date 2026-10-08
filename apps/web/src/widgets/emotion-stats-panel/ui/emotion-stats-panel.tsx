"use client";

import Link from "next/link";

import { EMOTION_LABELS, MonsterSprite } from "@/entities/monster";
import { Button, Card, Spinner } from "@/shared/ui";

import { useEmotionStatsQuery } from "../api/queries";
import type { EmotionStats } from "../model/types";
import { DistributionBar } from "./distribution-bar";
import { WeeklyChart } from "./weekly-chart";

/**
 * 마이페이지 감정 통계(004 US4): 몬스터 수, 감정 분포, 가장 많이 나타난 몬스터, 최근 8주 추이,
 * 함께 물리친 몬스터. 내 몬스터가 아직 없으면 분포와 추이 대신 안내와 글쓰기를 보인다(US4-AC4).
 * 함께 물리친 수는 남의 글에서 생기므로 내 몬스터가 없어도 보인다.
 */
export function EmotionStatsPanel() {
  const { data: stats, isPending, isError } = useEmotionStatsQuery();

  return (
    <section aria-labelledby="emotion-stats-heading">
      <Card className="flex w-full flex-col gap-5">
        <h2
          id="emotion-stats-heading"
          className="text-base font-semibold text-neutral-900"
        >
          감정 통계
        </h2>
        {isPending ? (
          <div className="flex justify-center py-4">
            <Spinner />
          </div>
        ) : isError || !stats ? (
          <p role="alert" className="text-sm text-red-600">
            감정 통계를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
          </p>
        ) : (
          <Stats stats={stats} />
        )}
      </Card>
    </section>
  );
}

function Stats({ stats }: { stats: EmotionStats }) {
  return (
    <>
      <dl className="grid grid-cols-3 gap-3 text-center">
        <Figure label="내 몬스터" value={stats.totalMonsters} />
        <Figure label="물리친 몬스터" value={stats.defeatedMonsters} />
        <Figure label="함께 물리친 몬스터" value={stats.defeatedTogether} />
      </dl>
      {stats.totalMonsters === 0 || stats.topEmotion === null ? (
        <div className="flex flex-col items-center gap-3 text-center">
          <p className="text-sm text-neutral-600">
            아직 몬스터가 없어요. 고민을 쓰면 감정 몬스터가 나타나요.
          </p>
          <Button asChild size="sm">
            <Link href="/write">고민 쓰기</Link>
          </Button>
        </div>
      ) : (
        <>
          <div className="flex items-center gap-3">
            <div className="size-16 shrink-0">
              <MonsterSprite
                emotion={stats.topEmotion}
                stage="full"
                sizes="64px"
              />
            </div>
            <p className="text-sm text-neutral-600">
              가장 많이 나타난 몬스터
              <strong className="block text-base font-semibold text-neutral-900">
                {EMOTION_LABELS[stats.topEmotion]}
              </strong>
            </p>
          </div>
          <DistributionBar
            distribution={stats.distribution}
            labels={EMOTION_LABELS}
          />
          <div className="flex flex-col gap-2">
            <h3 className="text-sm font-medium text-neutral-900">최근 8주</h3>
            <WeeklyChart weekly={stats.weekly} labels={EMOTION_LABELS} />
          </div>
        </>
      )}
    </>
  );
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    // 이름표가 두 줄로 넘어가도 숫자끼리 높이가 맞게, 숫자를 위에 둔다.
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="order-first text-2xl font-semibold text-neutral-900 tabular-nums">
        {value}
      </dd>
    </div>
  );
}
