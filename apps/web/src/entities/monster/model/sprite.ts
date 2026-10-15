import type { HpStage } from "./hp-stage";
import { emotionLabel, type EmotionType } from "./types";

/** HP 단계의 한국어 이름. 그림의 대체 텍스트에 쓴다. */
export const STAGE_LABELS: Record<HpStage, string> = {
  full: "멀쩡함",
  hurt: "상처 입음",
  weak: "약해짐",
  defeated: "쓰러짐",
};

/**
 * 불안 몬스터의 모습 수(017). 불안 글이 많아 같은 그림이 줄지어 보이지 않게, 지금의 털뭉치(0)에
 * 젤리(1), 구름(2), 시계 토끼(3)를 더했다. 다른 감정은 한 모습이다.
 */
export const ANXIETY_LOOKS = 4;

/**
 * 글의 몬스터가 어떤 모습인지(017 R1). 글 번호의 나머지라 저장하지 않아도 모든 화면이 같은 답을 내고,
 * 번호가 이어지는 글끼리 모습이 겹치지 않는다. 글 번호가 없는 자리(감정 소개, 보스)는 0이다.
 */
export function monsterLook(emotion: EmotionType, postId?: number): number {
  if (emotion !== "ANXIETY" || postId === undefined) return 0;
  return ((postId % ANXIETY_LOOKS) + ANXIETY_LOOKS) % ANXIETY_LOOKS;
}

/**
 * 몬스터 그림의 경로(ADR-0006). 감정 5 × 단계 4 = 20장이 `public/monsters/`에 있다.
 * 불안의 다른 모습은 `anxiety-v{모습+1}-{단계}.webp` 12장이다(017).
 * 보스는 감정마다 살아 있는 모습과 쓰러진 모습 두 장이다. 살아 있는 동안의 단계는 같은 그림을 쓴다.
 */
export function spritePath(
  emotion: EmotionType,
  stage: HpStage,
  boss = false,
  look = 0,
): string {
  const name = emotion.toLowerCase();
  if (boss) {
    return `/monsters/boss-${name}${stage === "defeated" ? "-defeated" : ""}.webp`;
  }
  if (emotion === "ANXIETY" && look > 0) {
    return `/monsters/${name}-v${look + 1}-${stage}.webp`;
  }
  return `/monsters/${name}-${stage}.webp`;
}

/** 화면 읽기 도구가 읽을 이름. 예: "불안 몬스터, 상처 입음", "불안 보스, 쓰러짐". */
export function monsterLabel(
  emotion: EmotionType,
  stage: HpStage,
  boss = false,
): string {
  return `${emotionLabel(emotion)} ${boss ? "보스" : "몬스터"}, ${STAGE_LABELS[stage]}`;
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
