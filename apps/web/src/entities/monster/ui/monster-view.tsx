"use client";

import dynamic from "next/dynamic";
import { useRef } from "react";

import { cn } from "@/shared/lib";

import { appearance } from "../model/appearance";
import { monsterLabel } from "../model/sprite";
import { EMOTION_LABELS, type MonsterView } from "../model/types";
import { MonsterSprite } from "./monster-sprite";
import { useCanRender3D } from "./render-mode";
import { useHitReaction } from "./use-hit-reaction";

/** three와 R3F는 이 동적 import로만 불러온다. 첫 로딩 번들에 들어가지 않는다(ADR-0003). */
const Monster3D = dynamic(() => import("./monster-3d"), {
  ssr: false,
  loading: () => <div aria-hidden className="size-full" />,
});

const DETAIL_SIZES = "(min-width: 640px) 224px, 192px";

/**
 * 몬스터 자리(US5). 감정 이름, 몬스터 그림, HP 바와 숫자, 처치됨 표시를 보여 준다.
 *
 * - `card`(피드): 항상 정지 이미지다(US5-AC3). 피드에 WebGL 캔버스를 여러 개 띄우지 않는다.
 * - `detail`(글 상세): WebGL을 쓸 수 있고 움직임 줄이기가 꺼져 있으면 3D 장면,
 *   아니면 정지 이미지다(US5-AC4).
 *
 * HP가 줄면 맞는 반응을 한다(US3-AC10). 정지 이미지면 그림과 HP 바를 함께 흔들고,
 * 3D면 장면이 0.4초 흔들리며 깜빡이고 HP 바만 흔든다. 움직임 줄이기면 흔들지 않는다.
 */
export function MonsterDisplay({
  monster,
  variant,
  className,
}: {
  monster: MonsterView;
  variant: "detail" | "card";
  className?: string;
}) {
  const can3D = useCanRender3D();
  const use3D = variant === "detail" && can3D;
  const look = appearance(
    monster.emotion,
    monster.hp / monster.maxHp,
    monster.status,
  );
  const label = monsterLabel(monster.emotion, look.stage);
  const percent = Math.round((monster.hp / monster.maxHp) * 100);

  const rootRef = useRef<HTMLDivElement>(null);
  const hpRef = useRef<HTMLDivElement>(null);
  useHitReaction(monster.hp, use3D ? hpRef : rootRef);

  const sprite = (
    <MonsterSprite
      emotion={monster.emotion}
      stage={look.stage}
      sizes={variant === "card" ? "64px" : DETAIL_SIZES}
    />
  );

  const detail = variant === "detail";

  return (
    <div
      ref={rootRef}
      className={cn(
        detail ? "flex flex-col items-center gap-3" : "flex items-center gap-3",
        className,
      )}
    >
      <div
        className={cn("shrink-0", detail ? "size-48 sm:size-56" : "size-16")}
      >
        {use3D ? (
          <Monster3D
            look={look}
            hp={monster.hp}
            label={label}
            fallback={sprite}
            className="size-full"
          />
        ) : (
          sprite
        )}
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
    </div>
  );
}
