import type { HpStage } from "./hp-stage";
import { EMOTION_LABELS, type EmotionType } from "./types";

/** HP 단계의 한국어 이름. 정지 이미지의 대체 텍스트와 3D 장면의 이름에 쓴다. */
export const STAGE_LABELS: Record<HpStage, string> = {
  full: "멀쩡함",
  hurt: "상처 입음",
  weak: "약해짐",
  defeated: "쓰러짐",
};

/** 감정 5 × 단계 4 = 20장의 정지 이미지 경로(`scripts/render-monsters.ts`가 만든다). */
export function spritePath(emotion: EmotionType, stage: HpStage): string {
  return `/monsters/${emotion.toLowerCase()}-${stage}.png`;
}

/** 화면 읽기 도구가 읽을 몬스터 이름. 예: "불안 몬스터, 상처 입음". */
export function monsterLabel(emotion: EmotionType, stage: HpStage): string {
  return `${EMOTION_LABELS[emotion]} 몬스터, ${STAGE_LABELS[stage]}`;
}
