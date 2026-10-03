"use client";

import { useRef } from "react";

import { cn } from "@/shared/lib";

import { EMOTION_LABELS, type MonsterView } from "../model/types";
import { useHitReaction } from "./use-hit-reaction";

export { HIT_REACTION_MS } from "./use-hit-reaction";

/**
 * 3D 몬스터(US5, T053) 전까지 쓰는 임시 몬스터 자리. 감정 이름과 HP 바만 보여 준다.
 * T053에서 `monster-view.tsx`로 바꾼다. 그때까지 맞는 반응은 HP 바 흔들림이고(US3-AC10),
 * 처치된 모습은 "처치됨" 표시다(US3-AC5).
 */
export function MonsterPlaceholder({
  monster,
  className,
}: {
  monster: MonsterView;
  className?: string;
}) {
  const percent = Math.round((monster.hp / monster.maxHp) * 100);
  const hpRef = useRef<HTMLDivElement>(null);
  useHitReaction(monster.hp, hpRef);

  return (
    <div className={cn("flex flex-col gap-2", className)}>
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
      <div ref={hpRef} className="flex flex-col gap-2">
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
  );
}
