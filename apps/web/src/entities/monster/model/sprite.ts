import type { HpStage } from "./hp-stage";
import { EMOTION_LABELS, type EmotionType } from "./types";

/** HP 단계의 한국어 이름. 그림의 대체 텍스트에 쓴다. */
export const STAGE_LABELS: Record<HpStage, string> = {
  full: "멀쩡함",
  hurt: "상처 입음",
  weak: "약해짐",
  defeated: "쓰러짐",
};

/**
 * 몬스터 그림의 경로(ADR-0006). 감정 5 × 단계 4 = 20장이 `public/monsters/`에 있다.
 * 보스는 감정마다 살아 있는 모습과 쓰러진 모습 두 장이다. 살아 있는 동안의 단계는 같은 그림을 쓴다.
 */
export function spritePath(
  emotion: EmotionType,
  stage: HpStage,
  boss = false,
): string {
  const name = emotion.toLowerCase();
  if (boss) {
    return `/monsters/boss-${name}${stage === "defeated" ? "-defeated" : ""}.webp`;
  }
  return `/monsters/${name}-${stage}.webp`;
}

/** 화면 읽기 도구가 읽을 이름. 예: "불안 몬스터, 상처 입음", "불안 보스, 쓰러짐". */
export function monsterLabel(
  emotion: EmotionType,
  stage: HpStage,
  boss = false,
): string {
  return `${EMOTION_LABELS[emotion]} ${boss ? "보스" : "몬스터"}, ${STAGE_LABELS[stage]}`;
}

/** 감정별 대기 움직임: 떨림, 축 처짐, 좌우 흔들림, 끄덕임, 움찔거림. `globals.css`의 `monster-idle-*`와 짝이다. */
export type MonsterMotion = "tremble" | "sag" | "sway" | "nod" | "twitch";

export const EMOTION_MOTION: Record<EmotionType, MonsterMotion> = {
  ANXIETY: "tremble",
  LETHARGY: "sag",
  LONELINESS: "sway",
  SELF_DEPRECATION: "nod",
  IRRITATION: "twitch",
};
