/**
 * 정지 이미지 캡처용 하네스(scripts/render-monsters.ts가 Vite로 띄운다).
 * Next 앱 밖이라 배포되지 않는다. `?emotion=ANXIETY&stage=hurt`의 장면을 멈춘 채
 * 그리고, 첫 장면을 그리면 `window.__monsterReady`를 켠다.
 */
import { createRoot } from "react-dom/client";

import { appearance } from "@/entities/monster/model/appearance";
import type { HpStage } from "@/entities/monster/model/hp-stage";
import { monsterLabel } from "@/entities/monster/model/sprite";
import type { EmotionType } from "@/entities/monster/model/types";
import Monster3D from "@/entities/monster/ui/monster-3d";

/** 단계마다 대표 HP 비율. 외형은 단계로만 바뀌므로 단계 안의 어느 값이든 같다. */
const STAGE_RATIO: Record<HpStage, number> = {
  full: 1,
  hurt: 0.5,
  weak: 0.2,
  defeated: 0,
};

const params = new URLSearchParams(window.location.search);
const emotion = params.get("emotion") as EmotionType;
const stage = params.get("stage") as HpStage;
const look = appearance(
  emotion,
  STAGE_RATIO[stage],
  stage === "defeated" ? "DEFEATED" : "ALIVE",
);
if (look.stage !== stage) {
  throw new Error(`단계가 맞지 않는다: ${stage} -> ${look.stage}`);
}

createRoot(document.getElementById("root")!).render(
  <Monster3D
    look={look}
    hp={STAGE_RATIO[stage]}
    label={monsterLabel(emotion, stage)}
    still
    className="capture"
    onReady={() => {
      (window as unknown as { __monsterReady: boolean }).__monsterReady = true;
    }}
  />,
);
