import { describe, expect, it } from "vitest";

import { appearance, type MonsterAppearance } from "./appearance";
import type { EmotionType } from "./types";

const EMOTIONS: EmotionType[] = [
  "ANXIETY",
  "LETHARGY",
  "LONELINESS",
  "SELF_DEPRECATION",
  "IRRITATION",
];

/** 단계마다 대표 HP 비율: 가득, 2/3 아래, 1/3 아래, 0. */
const BY_STAGE = (emotion: EmotionType) => ({
  full: appearance(emotion, 1, "ALIVE"),
  hurt: appearance(emotion, 0.5, "ALIVE"),
  weak: appearance(emotion, 0.2, "ALIVE"),
  defeated: appearance(emotion, 0, "DEFEATED"),
});

function allDistinct(values: unknown[]): boolean {
  const keys = values.map((value) => JSON.stringify(value));
  return new Set(keys).size === keys.length;
}

describe("appearance", () => {
  it("US5-AC1 감정 5종은 색, 형태, 움직임 파라미터가 서로 다르다", () => {
    const looks = EMOTIONS.map((emotion) => appearance(emotion, 1, "ALIVE"));

    expect(allDistinct(looks.map((look) => look.hue))).toBe(true);
    expect(allDistinct(looks.map((look) => look.color))).toBe(true);
    expect(allDistinct(looks.map((look) => look.shape))).toBe(true);
    expect(allDistinct(looks.map((look) => look.motion.kind))).toBe(true);
    expect(
      allDistinct(
        looks.map((look) => [look.motion.amplitude, look.motion.frequency]),
      ),
    ).toBe(true);
  });

  it("US5-AC1 감정별 형태는 research R11을 따른다", () => {
    expect(appearance("ANXIETY", 1, "ALIVE")).toMatchObject({
      shape: "spiky",
      motion: { kind: "tremble" },
    });
    expect(appearance("LETHARGY", 1, "ALIVE").shape).toBe("droop");
    expect(appearance("LONELINESS", 1, "ALIVE").shape).toBe("curled");
    expect(appearance("SELF_DEPRECATION", 1, "ALIVE").shape).toBe("bowed");
    expect(appearance("IRRITATION", 1, "ALIVE").shape).toBe("angular");
    // 외로움은 작은 몸에 큰 눈이다
    const lonely = appearance("LONELINESS", 1, "ALIVE");
    for (const other of EMOTIONS.filter((e) => e !== "LONELINESS")) {
      const look = appearance(other, 1, "ALIVE");
      expect(lonely.eyeScale).toBeGreaterThan(look.eyeScale);
      expect(lonely.scale).toBeLessThan(look.scale);
    }
  });

  it.each(EMOTIONS)(
    "US5-AC2 HP 비율이 낮아질수록 크기와 채도가 줄고 금 간 정도가 늘며 0이면 쓰러짐 (%s)",
    (emotion) => {
      const { full, hurt, weak, defeated } = BY_STAGE(emotion);
      const order: MonsterAppearance[] = [full, hurt, weak, defeated];

      expect(order.map((look) => look.stage)).toEqual([
        "full",
        "hurt",
        "weak",
        "defeated",
      ]);
      for (let i = 1; i < order.length; i += 1) {
        expect(order[i].scale).toBeLessThan(order[i - 1].scale);
        expect(order[i].saturation).toBeLessThan(order[i - 1].saturation);
        expect(order[i].crack).toBeGreaterThan(order[i - 1].crack);
      }
      expect(full.crack).toBe(0);
      expect([full.fallen, hurt.fallen, weak.fallen]).toEqual([
        false,
        false,
        false,
      ]);
      expect(defeated.fallen).toBe(true);
      expect(defeated.expression).toBe("knocked");
      expect(defeated.motion.amplitude).toBe(0);
      // 감정은 단계가 바뀌어도 그대로 알아볼 수 있다(같은 형태와 색상)
      expect(new Set(order.map((look) => look.shape)).size).toBe(1);
      expect(new Set(order.map((look) => look.hue)).size).toBe(1);
    },
  );

  describe("US5-AC2 단계 경계(66%, 33%, 0)", () => {
    it.each([
      // 66% 경계: 2/3을 넘어야 full이다. 20/30은 정확히 2/3이라 hurt다(hp-stage.ts와 같다).
      [1, "full"],
      [0.67, "full"],
      [21 / 30, "full"],
      [20 / 30, "hurt"],
      [0.66, "hurt"],
      // 33% 경계: 1/3을 넘어야 hurt다. 10/30은 정확히 1/3이라 weak다.
      [0.34, "hurt"],
      [11 / 30, "hurt"],
      [10 / 30, "weak"],
      [0.33, "weak"],
      [1 / 30, "weak"],
      // 0이면 쓰러진다
      [0, "defeated"],
    ])("hpRatio=%f -> %s", (ratio, stage) => {
      const look = appearance(
        "ANXIETY",
        ratio,
        ratio === 0 ? "DEFEATED" : "ALIVE",
      );
      expect(look.stage).toBe(stage);
      expect(look.fallen).toBe(stage === "defeated");
    });

    it("상태가 DEFEATED면 HP 비율과 상관없이 쓰러진 모습이다", () => {
      const look = appearance("IRRITATION", 0.5, "DEFEATED");
      expect(look.stage).toBe("defeated");
      expect(look.fallen).toBe(true);
    });

    it("같은 단계 안에서는 HP 비율이 달라도 외형이 같다(정지 이미지 20장과 맞는다)", () => {
      expect(appearance("LETHARGY", 0.9, "ALIVE")).toEqual(
        appearance("LETHARGY", 0.7, "ALIVE"),
      );
      expect(appearance("LETHARGY", 0.3, "ALIVE")).toEqual(
        appearance("LETHARGY", 0.05, "ALIVE"),
      );
    });
  });

  it("같은 입력은 같은 출력을 돌려준다(순수 함수)", () => {
    for (const emotion of EMOTIONS) {
      for (const ratio of [1, 0.5, 0.2, 0]) {
        const status = ratio === 0 ? "DEFEATED" : "ALIVE";
        const first = appearance(emotion, ratio, status);
        const second = appearance(emotion, ratio, status);
        expect(second).toEqual(first);
        expect(second).not.toBe(first);
      }
    }
  });

  it("색은 #rrggbb 문자열이다", () => {
    for (const emotion of EMOTIONS) {
      for (const look of Object.values(BY_STAGE(emotion))) {
        expect(look.color).toMatch(/^#[0-9a-f]{6}$/);
      }
    }
  });
});
