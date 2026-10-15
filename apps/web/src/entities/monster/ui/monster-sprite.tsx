import Image from "next/image";

import { cn } from "@/shared/lib";

import type { HpStage } from "../model/hp-stage";
import { monsterLabel, monsterLook, spritePath } from "../model/sprite";
import type { EmotionType } from "../model/types";

/**
 * 몬스터 그림(ADR-0006). `/monsters/{emotion}-{stage}.webp`를 쓴다. `boss`면 보스 그림이다.
 * 보스는 살아 있는 동안 한 장을 쓰므로, HP가 줄어든 것은 색을 조금 빼서 보인다.
 * `postId`를 넘기면 그 글의 모습으로 그린다(017). 대체 텍스트는 모습과 상관없이 같다.
 */
export function MonsterSprite({
  emotion,
  stage,
  sizes,
  boss = false,
  postId,
  className,
}: {
  emotion: EmotionType;
  stage: HpStage;
  /** `next/image`가 고를 크기. 화면에 실제로 그려지는 너비. */
  sizes: string;
  boss?: boolean;
  /** 글의 몬스터면 그 글의 번호. 불안 몬스터의 모습을 고른다(017). */
  postId?: number;
  className?: string;
}) {
  return (
    <Image
      src={spritePath(emotion, stage, boss, monsterLook(emotion, postId))}
      alt={monsterLabel(emotion, stage, boss)}
      width={512}
      height={512}
      sizes={sizes}
      draggable={false}
      className={cn(
        "size-full object-contain select-none",
        boss && stage === "hurt" && "saturate-[.8]",
        boss && stage === "weak" && "saturate-[.55]",
        className,
      )}
    />
  );
}
