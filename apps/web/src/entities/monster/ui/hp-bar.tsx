import { cn } from "@/shared/lib";

import type { EmotionType } from "../model/types";

/**
 * HP를 0~100의 정수 퍼센트로 바꾼다. 범위를 벗어나거나 읽을 수 없는 값은 0~100으로 자른다.
 * 살아 있는 몬스터가 빈 막대로, 깎인 몬스터가 꽉 찬 막대로 보이지 않게 그 사이는 1~99로 둔다
 * (보스는 최대 HP가 커서 반올림하면 마지막 몇십 HP가 0%가 된다).
 */
export function hpPercent(hp: number, maxHp: number): number {
  if (!Number.isFinite(hp) || !Number.isFinite(maxHp) || maxHp <= 0) return 0;
  if (hp <= 0) return 0;
  if (hp >= maxHp) return 100;
  return Math.min(99, Math.max(1, Math.round((hp / maxHp) * 100)));
}

/**
 * HP 막대. `sm`은 피드의 6px 막대, `lg`는 글 상세와 레이드의 22px 외곽선 막대다.
 * 색만으로 전하지 않게, 쓰는 곳에서 HP 숫자를 글자로 함께 보인다.
 */
export function HpBar({
  emotion,
  hp,
  maxHp,
  size = "sm",
  label = "몬스터 HP",
  className,
}: {
  emotion: EmotionType;
  hp: number;
  maxHp: number;
  size?: "sm" | "lg";
  label?: string;
  className?: string;
}) {
  const percent = hpPercent(hp, maxHp);
  const max = Number.isFinite(maxHp) && maxHp > 0 ? maxHp : 0;
  const now = Number.isFinite(hp) ? Math.min(Math.max(0, hp), max) : 0;

  return (
    <div
      data-emotion={emotion}
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={now}
      className={cn(
        "w-full overflow-hidden rounded-full",
        size === "lg"
          ? "border-game-line bg-surface h-[22px] border-2"
          : "bg-line h-1.5",
        className,
      )}
    >
      <div
        className={cn(
          "bg-emotion h-full transition-[width] duration-200 motion-reduce:transition-none",
          size === "lg" &&
            percent > 0 &&
            percent < 100 &&
            "border-game-line border-r-2",
        )}
        style={{ width: `${percent}%` }}
      />
    </div>
  );
}
