import { cn } from "@/shared/lib";

import { EMOTION_LABELS, type MonsterView } from "../model/types";

/**
 * 3D 몬스터(US5, T053) 전까지 쓰는 임시 몬스터 자리. 감정 이름과 HP 바만 보여 준다.
 * T053에서 `monster-view.tsx`로 바꾼다.
 */
export function MonsterPlaceholder({
  monster,
  className,
}: {
  monster: MonsterView;
  className?: string;
}) {
  const percent = Math.round((monster.hp / monster.maxHp) * 100);

  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <p className="text-lg font-semibold text-neutral-900">
        {EMOTION_LABELS[monster.emotion]}
      </p>
      <div
        role="progressbar"
        aria-label="몬스터 HP"
        aria-valuemin={0}
        aria-valuemax={monster.maxHp}
        aria-valuenow={monster.hp}
        className="h-3 w-full overflow-hidden rounded-full bg-neutral-200"
      >
        <div
          className="h-full rounded-full bg-red-500 transition-[width]"
          style={{ width: `${percent}%` }}
        />
      </div>
      <p className="text-xs text-neutral-500 tabular-nums">
        HP {monster.hp}/{monster.maxHp}
      </p>
    </div>
  );
}
