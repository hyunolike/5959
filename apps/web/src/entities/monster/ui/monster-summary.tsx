import { cn } from "@/shared/lib";

import { hpStageOfRatio, type HpStage } from "../model/hp-stage";
import type { MonsterView } from "../model/types";
import { HpBar } from "./hp-bar";
import { MonsterThumb } from "./monster-thumb";

/** 목록에 쓰는 몬스터 요약: 48px 그림, HP 숫자, 작은 HP 막대. 처치됐으면 "처치됨"을 붙인다. */
export function MonsterSummary({
  monster,
  className,
}: {
  monster: Pick<MonsterView, "emotion" | "status"> & {
    hp: number;
    maxHp: number;
  };
  className?: string;
}) {
  const defeated = monster.status === "DEFEATED";
  const stage: HpStage = defeated
    ? "defeated"
    : hpStageOfRatio(monster.hp / monster.maxHp);

  return (
    <div className={cn("flex items-center gap-3", className)}>
      <MonsterThumb emotion={monster.emotion} stage={stage} />
      <div
        data-emotion={monster.emotion}
        className="flex w-28 shrink-0 flex-col gap-1.5"
      >
        <p className="text-caption text-emotion-fg flex flex-wrap items-center gap-x-1.5 gap-y-0.5 font-bold tabular-nums">
          <span className="whitespace-nowrap">
            HP {monster.hp}/{monster.maxHp}
          </span>
          {defeated ? (
            <span className="bg-action text-on-action rounded-full px-1.5 font-medium whitespace-nowrap">
              처치됨
            </span>
          ) : null}
        </p>
        <HpBar
          emotion={monster.emotion}
          hp={monster.hp}
          maxHp={monster.maxHp}
        />
      </div>
    </div>
  );
}
