import Image from "next/image";

import { cn } from "@/shared/lib";

import type { HpStage } from "../model/hp-stage";
import { monsterLabel, spritePath } from "../model/sprite";
import type { EmotionType } from "../model/types";

/**
 * 정지 이미지 몬스터(US5-AC3, US5-AC4). 3D 장면을 빌드 전에 캡처한
 * `/monsters/{emotion}-{stage}.png`를 쓴다. 피드 카드와 3D를 못 쓰는 상세 화면이 쓴다.
 */
export function MonsterSprite({
  emotion,
  stage,
  sizes,
  className,
}: {
  emotion: EmotionType;
  stage: HpStage;
  /** `next/image`가 고를 크기. 화면에 실제로 그려지는 너비. */
  sizes: string;
  className?: string;
}) {
  return (
    <Image
      src={spritePath(emotion, stage)}
      alt={monsterLabel(emotion, stage)}
      width={512}
      height={512}
      sizes={sizes}
      draggable={false}
      className={cn("size-full object-contain select-none", className)}
    />
  );
}
