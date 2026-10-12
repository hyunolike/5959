"use client";

import { useRef } from "react";

import { cn } from "@/shared/lib";

import { hpStageOfRatio, type HpStage } from "../model/hp-stage";
import { EMOTION_MOTION, STAGE_LABELS } from "../model/sprite";
import type { MonsterView } from "../model/types";
import { EmotionTag } from "./emotion-tag";
import { HpBar } from "./hp-bar";
import { MonsterSprite } from "./monster-sprite";
import { useHitReaction } from "./use-hit-reaction";

const DETAIL_SIZES = "(min-width: 640px) 224px, 176px";
const BOSS_SIZES = "(min-width: 640px) 288px, 248px";

/**
 * 무대에 올리는 큰 몬스터(US5): 그림, 감정 태그와 단계 이름, 큰 HP 막대와 숫자.
 *
 * - `detail`(글 상세), `boss`(레이드): 같은 짜임이고 보스는 더 크게, 보스 그림으로 그린다(006 research R14).
 *   감정마다 다른 대기 움직임을 준다(떨림, 축 처짐 등). 쓰러진 몬스터는 움직이지 않고 "처치됨"을 보인다.
 *   움직임 줄이기가 켜져 있으면 CSS가 움직임을 끈다.
 * - 목록의 작은 몬스터는 `MonsterSummary`를 쓴다.
 *
 * HP가 줄면 그림과 HP 막대를 함께 흔든다(US3-AC10). 움직임 줄이기면 흔들지 않는다.
 * HP 글자("HP n/m")는 "처치됨"과 다른 요소에 둔다. e2e가 HP 글자만 골라 읽는다.
 * 그림은 ADR-0006의 캐릭터 그림이다.
 */
export function MonsterDisplay({
  monster,
  variant,
  className,
}: {
  /** 글의 몬스터이거나 레이드 보스다. 보스는 최대 HP가 훨씬 크다(006). */
  monster: Pick<MonsterView, "emotion" | "status"> & {
    hp: number;
    maxHp: number;
  };
  variant: "detail" | "boss";
  className?: string;
}) {
  const defeated = monster.status === "DEFEATED";
  const stage: HpStage = defeated
    ? "defeated"
    : hpStageOfRatio(monster.hp / monster.maxHp);
  const boss = variant === "boss";

  const rootRef = useRef<HTMLDivElement>(null);
  useHitReaction(monster.hp, rootRef);

  return (
    <div
      ref={rootRef}
      data-emotion={monster.emotion}
      className={cn("flex flex-col items-center gap-3", className)}
    >
      <div
        className={cn(
          "shrink-0",
          boss ? "size-62 sm:size-72" : "size-44 sm:size-56",
        )}
      >
        <MonsterSprite
          emotion={monster.emotion}
          stage={stage}
          boss={boss}
          sizes={boss ? BOSS_SIZES : DETAIL_SIZES}
          className={
            defeated
              ? undefined
              : `monster-idle-${EMOTION_MOTION[monster.emotion]}`
          }
        />
      </div>
      <div className="flex items-center gap-2">
        <EmotionTag emotion={monster.emotion} className="bg-surface" />
        {defeated ? (
          <span className="bg-action text-caption text-on-action rounded-full px-2 py-0.5 font-medium">
            처치됨
          </span>
        ) : (
          <span className="text-label text-emotion-fg font-bold">
            {STAGE_LABELS[stage]}
          </span>
        )}
      </div>
      <div className="flex w-full items-center gap-3">
        <HpBar
          emotion={monster.emotion}
          hp={monster.hp}
          maxHp={monster.maxHp}
          size="lg"
          className="flex-1"
        />
        <p className="text-body-sm text-fg font-extrabold whitespace-nowrap tabular-nums">
          HP {monster.hp}/{monster.maxHp}
        </p>
      </div>
    </div>
  );
}
