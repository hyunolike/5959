import { cn } from "@/shared/lib";

import type { HpStage } from "../model/hp-stage";
import type { EmotionType } from "../model/types";
import { MonsterSprite } from "./monster-sprite";

/** 피드에 쓰는 48px 몬스터. 감정의 옅은 색을 바탕에 깐다. */
export function MonsterThumb({
  emotion,
  stage,
  className,
}: {
  emotion: EmotionType;
  stage: HpStage;
  className?: string;
}) {
  return (
    <span
      data-emotion={emotion}
      className={cn(
        "rounded-control bg-emotion-tint inline-flex size-12 shrink-0 items-center justify-center p-0.5",
        className,
      )}
    >
      <MonsterSprite emotion={emotion} stage={stage} sizes="48px" />
    </span>
  );
}
