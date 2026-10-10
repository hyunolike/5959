"use client";

import { useRef } from "react";

import { cn } from "@/shared/lib";

import { hpStageOfRatio, type HpStage } from "../model/hp-stage";
import { EMOTION_MOTION } from "../model/sprite";
import { EMOTION_LABELS, type MonsterView } from "../model/types";
import { MonsterSprite } from "./monster-sprite";
import { useHitReaction } from "./use-hit-reaction";

const DETAIL_SIZES = "(min-width: 640px) 224px, 192px";
const BOSS_SIZES = "(min-width: 640px) 288px, 240px";

/**
 * 몬스터 자리(US5). 감정 이름, 몬스터 그림, HP 바와 숫자, 처치됨 표시를 보여 준다.
 *
 * - `card`(피드): 그림만 보인다.
 * - `detail`(글 상세), `boss`(레이드): 같은 그림을 크게 보이고 감정마다 다른 대기 움직임을 준다(떨림, 축 처짐 등).
 *   쓰러진 몬스터는 움직이지 않는다. 움직임 줄이기가 켜져 있으면 CSS가 움직임을 끈다.
 *
 * HP가 줄면 그림과 HP 바를 함께 흔든다(US3-AC10). 움직임 줄이기면 흔들지 않는다.
 * 그림은 ADR-0006의 캐릭터 그림이다. 앞서 쓰던 코드 생성 3D(ADR-0003)는 걷어 냈다.
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
  /** `boss`는 `detail`과 같되 더 크게, 보스 그림으로 그린다(006 research R14). */
  variant: "detail" | "card" | "boss";
  className?: string;
}) {
  const stage: HpStage =
    monster.status === "DEFEATED"
      ? "defeated"
      : hpStageOfRatio(monster.hp / monster.maxHp);
  const percent = Math.round((monster.hp / monster.maxHp) * 100);

  const rootRef = useRef<HTMLDivElement>(null);
  useHitReaction(monster.hp, rootRef);

  const detail = variant !== "card";

  return (
    <div
      ref={rootRef}
      className={cn(
        detail ? "flex flex-col items-center gap-3" : "flex items-center gap-3",
        className,
      )}
    >
      <div
        className={cn(
          "shrink-0",
          variant === "boss"
            ? "size-60 sm:size-72"
            : detail
              ? "size-48 sm:size-56"
              : "size-16",
        )}
      >
        <MonsterSprite
          emotion={monster.emotion}
          stage={stage}
          boss={variant === "boss"}
          sizes={
            variant === "card"
              ? "64px"
              : variant === "boss"
                ? BOSS_SIZES
                : DETAIL_SIZES
          }
          className={
            detail && stage !== "defeated"
              ? `monster-idle-${EMOTION_MOTION[monster.emotion]}`
              : undefined
          }
        />
      </div>
      <div className="flex w-full min-w-0 flex-col gap-2">
        <div className="flex items-center gap-2">
          <p className="text-lg font-semibold text-neutral-900">
            {EMOTION_LABELS[monster.emotion]}
          </p>
          {monster.status === "DEFEATED" ? (
            <span className="rounded-full bg-neutral-900 px-2 py-0.5 text-xs text-white">
              처치됨
            </span>
          ) : null}
        </div>
        <div className="flex flex-col gap-2">
          <div
            role="progressbar"
            aria-label="몬스터 HP"
            aria-valuemin={0}
            aria-valuemax={monster.maxHp}
            aria-valuenow={monster.hp}
            className="h-3 w-full overflow-hidden rounded-full bg-neutral-200"
          >
            <div
              className="h-full rounded-full bg-red-500 transition-[width] motion-reduce:transition-none"
              style={{ width: `${percent}%` }}
            />
          </div>
          <p className="text-xs text-neutral-500 tabular-nums">
            HP {monster.hp}/{monster.maxHp}
          </p>
        </div>
      </div>
    </div>
  );
}
