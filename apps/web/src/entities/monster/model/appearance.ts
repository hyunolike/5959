import { hpStageOfRatio, type HpStage } from "./hp-stage";
import type { EmotionType, MonsterView } from "./types";

/** 감정별 몸의 형태. research R11. */
export type MonsterShape = "spiky" | "droop" | "curled" | "bowed" | "angular";

/** 감정별 대기 움직임. 떨림, 축 처짐, 좌우 흔들림, 끄덕임, 움찔거림. */
export type MonsterMotionKind = "tremble" | "sag" | "sway" | "nod" | "twitch";

/** 표정. 단계가 내려갈수록 지치고, 쓰러지면 눈이 X가 된다. */
export type MonsterExpression = "calm" | "worried" | "teary" | "knocked";

export type MonsterAppearance = {
  emotion: EmotionType;
  stage: HpStage;
  shape: MonsterShape;
  /** 색상(0~360). 단계가 바뀌어도 그대로라 감정을 알아볼 수 있다. */
  hue: number;
  /** 채도(0~1). HP가 줄수록 낮아진다. */
  saturation: number;
  /** 밝기(0~1). */
  lightness: number;
  /** 몸 색. hue, saturation, lightness를 `#rrggbb`로 바꾼 값. */
  color: string;
  /** 몸 크기 배율. HP가 줄수록 작아진다. */
  scale: number;
  /** 금 간 정도(0~1). 셰이더가 금의 개수와 굵기로 쓴다. */
  crack: number;
  /** 대기 움직임. amplitude는 장면 단위, frequency는 초당 횟수. */
  motion: { kind: MonsterMotionKind; amplitude: number; frequency: number };
  /** 눈 크기 배율. */
  eyeScale: number;
  expression: MonsterExpression;
  /** 쓰러진 모습(HP 0, 처치됨). */
  fallen: boolean;
};

type EmotionBase = Pick<
  MonsterAppearance,
  "shape" | "hue" | "saturation" | "lightness" | "scale" | "eyeScale"
> & { motion: MonsterAppearance["motion"] };

/** research R11의 감정별 형태. 불안은 떨리는 뾰족한 몸, 무기력은 축 처진 물방울,
 * 외로움은 작고 웅크린 몸과 큰 눈, 자기비하는 고개 숙인 몸, 짜증은 각지고 붉은 몸. */
const EMOTION_BASE: Record<EmotionType, EmotionBase> = {
  ANXIETY: {
    shape: "spiky",
    hue: 270,
    saturation: 0.55,
    lightness: 0.64,
    scale: 1,
    eyeScale: 1,
    motion: { kind: "tremble", amplitude: 0.03, frequency: 14 },
  },
  LETHARGY: {
    shape: "droop",
    hue: 222,
    saturation: 0.38,
    lightness: 0.62,
    scale: 1.05,
    eyeScale: 0.9,
    motion: { kind: "sag", amplitude: 0.06, frequency: 0.35 },
  },
  LONELINESS: {
    shape: "curled",
    hue: 192,
    saturation: 0.6,
    lightness: 0.7,
    scale: 0.82,
    eyeScale: 1.45,
    motion: { kind: "sway", amplitude: 0.14, frequency: 0.5 },
  },
  SELF_DEPRECATION: {
    shape: "bowed",
    hue: 84,
    saturation: 0.4,
    lightness: 0.56,
    scale: 0.95,
    eyeScale: 0.85,
    motion: { kind: "nod", amplitude: 0.1, frequency: 0.8 },
  },
  IRRITATION: {
    shape: "angular",
    hue: 6,
    saturation: 0.78,
    lightness: 0.56,
    scale: 1,
    eyeScale: 0.95,
    motion: { kind: "twitch", amplitude: 0.05, frequency: 3 },
  },
};

type StageModifier = {
  scale: number;
  saturation: number;
  lightness: number;
  crack: number;
  motion: number;
  expression: MonsterExpression;
};

/** 단계별 변화(US5-AC2). 아래로 갈수록 작아지고 색이 바래며 금이 늘어난다. */
const STAGE_MODIFIER: Record<HpStage, StageModifier> = {
  full: {
    scale: 1,
    saturation: 1,
    lightness: 1,
    crack: 0,
    motion: 1,
    expression: "calm",
  },
  hurt: {
    scale: 0.92,
    saturation: 0.7,
    lightness: 0.97,
    crack: 0.35,
    motion: 0.8,
    expression: "worried",
  },
  weak: {
    scale: 0.84,
    saturation: 0.42,
    lightness: 0.93,
    crack: 0.7,
    motion: 0.55,
    expression: "teary",
  },
  defeated: {
    scale: 0.78,
    saturation: 0.15,
    lightness: 0.88,
    crack: 1,
    motion: 0,
    expression: "knocked",
  },
};

function round(value: number, digits = 3): number {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

/** HSL(색상 0~360, 채도와 밝기 0~1)을 `#rrggbb`로 바꾼다. */
export function hslToHex(hue: number, saturation: number, lightness: number) {
  const chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
  const h = (((hue % 360) + 360) % 360) / 60;
  const x = chroma * (1 - Math.abs((h % 2) - 1));
  const [r, g, b] =
    h < 1
      ? [chroma, x, 0]
      : h < 2
        ? [x, chroma, 0]
        : h < 3
          ? [0, chroma, x]
          : h < 4
            ? [0, x, chroma]
            : h < 5
              ? [x, 0, chroma]
              : [chroma, 0, x];
  const m = lightness - chroma / 2;
  return `#${[r, g, b]
    .map((channel) =>
      Math.round((channel + m) * 255)
        .toString(16)
        .padStart(2, "0"),
    )
    .join("")}`;
}

/**
 * (감정, HP 비율, 상태) → 외형 파라미터. research R11, ADR-0003.
 * 외형은 HP 단계(full, hurt, weak, defeated)로만 바뀐다. 그래서 3D 장면과
 * 정지 이미지 20장(감정 5 × 단계 4)이 언제나 같은 모습이다.
 * 상태가 DEFEATED면 HP 비율과 상관없이 쓰러진 모습이다.
 */
export function appearance(
  emotion: EmotionType,
  hpRatio: number,
  status: MonsterView["status"],
): MonsterAppearance {
  const stage: HpStage =
    status === "DEFEATED" ? "defeated" : hpStageOfRatio(hpRatio);
  const base = EMOTION_BASE[emotion];
  const modifier = STAGE_MODIFIER[stage];
  const saturation = round(base.saturation * modifier.saturation);
  const lightness = round(base.lightness * modifier.lightness);

  return {
    emotion,
    stage,
    shape: base.shape,
    hue: base.hue,
    saturation,
    lightness,
    color: hslToHex(base.hue, saturation, lightness),
    scale: round(base.scale * modifier.scale),
    crack: modifier.crack,
    motion: {
      kind: base.motion.kind,
      amplitude: round(base.motion.amplitude * modifier.motion, 4),
      frequency: base.motion.frequency,
    },
    eyeScale: base.eyeScale,
    expression: modifier.expression,
    fallen: stage === "defeated",
  };
}
