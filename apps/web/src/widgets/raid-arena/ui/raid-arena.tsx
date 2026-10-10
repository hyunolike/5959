"use client";

import Link from "next/link";

import { EMOTION_LABELS, MonsterDisplay } from "@/entities/monster";
import {
  RAID_TOPIC,
  useRaidQuery,
  type RaidBoss,
  type RaidState,
} from "@/entities/raid";
import {
  useNotificationStreamStatus,
  useStreamTopic,
} from "@/features/notification-stream";
import { AttackButton } from "@/features/raid-attack";
import { formatDate } from "@/shared/lib";
import { Button, Card, Spinner } from "@/shared/ui";

/** 레이드가 쉬는 중일 때의 안내(006 US3-AC6). 공격은 반영되지 않는다. */
export const RAID_PAUSED_NOTICE =
  "레이드가 잠시 쉬고 있어요. 조금 뒤에 다시 공격할 수 있어요.";

/**
 * 레이드 화면의 본문(006 US1, US2, US4).
 *
 * - 이 화면이 보이는 동안 실시간 스트림에서 레이드 소식을 함께 받는다. 다른 회원의 공격으로 HP가 줄고
 *   참여자가 느는 것이 새로고침 없이 보인다. 연결이 열려 있지 않으면 3초마다 값을 다시 받는다(US2-AC6).
 * - 보이는 것은 보스의 상태, 참여자 수, 내 기여뿐이다. 다른 회원의 이름, 기여, 순위는 없다(US4-AC4).
 * - 보스가 끝나면 결과와 다음 보스가 나오는 때를 보인다. 공격 버튼은 없다(US2-AC7).
 */
export function RaidArena() {
  useStreamTopic(RAID_TOPIC);
  const streamOpen = useNotificationStreamStatus() === "open";
  const { data, isPending, isError } = useRaidQuery({ polling: !streamOpen });

  if (isPending) {
    return (
      <div className="flex justify-center py-8">
        <Spinner />
      </div>
    );
  }
  if (isError || data === undefined) {
    return (
      <Card>
        <p role="alert" className="text-sm text-red-600">
          레이드를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      </Card>
    );
  }
  if (data.boss === null) {
    return (
      <Card className="text-center">
        <p className="text-sm text-neutral-600">곧 첫 보스가 나타나요.</p>
      </Card>
    );
  }
  return data.boss.status === "ALIVE" ? (
    <Battle state={data} boss={data.boss} />
  ) : (
    <Result state={data} boss={data.boss} />
  );
}

function Battle({ state, boss }: { state: RaidState; boss: RaidBoss }) {
  return (
    <Card
      role="region"
      aria-label="보스"
      className="flex flex-col items-center gap-4"
    >
      <p className="text-sm text-neutral-600">
        요즘 가장 많이 나타난 감정이 보스가 됐어요. 함께 물리쳐요.
      </p>
      <MonsterDisplay
        monster={{
          emotion: boss.emotion,
          hp: boss.hp,
          maxHp: boss.maxHp,
          status: "ALIVE",
        }}
        variant="boss"
        className="w-full"
      />
      <Figures
        participantCount={boss.participantCount}
        myDamage={state.myDamage}
      />
      <AttackButton bossId={boss.bossId} disabled={!state.available} />
      {state.available ? null : (
        <p role="status" className="text-sm text-amber-800">
          {RAID_PAUSED_NOTICE}
        </p>
      )}
    </Card>
  );
}

function Result({ state, boss }: { state: RaidState; boss: RaidBoss }) {
  const defeated = boss.status === "DEFEATED";
  return (
    <Card
      role="region"
      aria-label="레이드 결과"
      className="flex flex-col items-center gap-4"
    >
      <h2 className="text-lg font-semibold text-neutral-900">
        {defeated
          ? `${EMOTION_LABELS[boss.emotion]} 보스를 함께 물리쳤어요`
          : `${EMOTION_LABELS[boss.emotion]} 보스가 물러났어요`}
      </h2>
      <MonsterDisplay
        monster={{
          emotion: boss.emotion,
          hp: boss.hp,
          maxHp: boss.maxHp,
          status: defeated ? "DEFEATED" : "ALIVE",
        }}
        variant="boss"
        className="w-full"
      />
      <Figures
        participantCount={boss.participantCount}
        myDamage={state.myDamage}
      >
        {boss.endedAt ? (
          <Figure
            label="걸린 시간"
            value={elapsedLabel(boss.spawnedAt, boss.endedAt)}
          />
        ) : null}
      </Figures>
      {state.nextBossAt ? (
        <p className="text-sm text-neutral-600">
          다음 보스는{" "}
          <time dateTime={state.nextBossAt}>
            {formatDate(state.nextBossAt, "M월 D일 HH:mm")}
          </time>
          에 나타나요.
        </p>
      ) : null}
      <Button asChild variant="ghost" size="sm">
        <Link href="/home">피드로</Link>
      </Button>
    </Card>
  );
}

function Figures({
  participantCount,
  myDamage,
  children,
}: {
  participantCount: number;
  myDamage: number;
  children?: React.ReactNode;
}) {
  return (
    // 공격이 몰려도 화면 낭독기가 따라올 수 있게, 바뀐 값만 정중하게 읽는다.
    <dl
      aria-live="polite"
      className="flex w-full justify-center gap-6 text-center"
    >
      <Figure label="함께한 회원" value={`${participantCount}명`} />
      <Figure label="내 기여" value={String(myDamage)} />
      {children}
    </dl>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-xs text-neutral-500">{label}</dt>
      <dd className="text-lg font-semibold text-neutral-900 tabular-nums">
        {value}
      </dd>
    </div>
  );
}

const MINUTE_MS = 60_000;
const HOUR_MINUTES = 60;
const DAY_MINUTES = 24 * HOUR_MINUTES;

/** 나타나서 끝날 때까지 걸린 시간을 가장 큰 단위 둘로 적는다. 1분이 안 되면 "1분 미만"이다. */
export function elapsedLabel(spawnedAt: string, endedAt: string): string {
  const minutes = Math.floor(
    (new Date(endedAt).getTime() - new Date(spawnedAt).getTime()) / MINUTE_MS,
  );
  if (minutes < 1) {
    return "1분 미만";
  }
  if (minutes < HOUR_MINUTES) {
    return `${minutes}분`;
  }
  if (minutes < DAY_MINUTES) {
    return `${Math.floor(minutes / HOUR_MINUTES)}시간 ${minutes % HOUR_MINUTES}분`;
  }
  const hours = Math.floor((minutes % DAY_MINUTES) / HOUR_MINUTES);
  return `${Math.floor(minutes / DAY_MINUTES)}일 ${hours}시간`;
}
