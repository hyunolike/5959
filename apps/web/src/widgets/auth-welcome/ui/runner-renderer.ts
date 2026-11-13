import {
  GRAVITY,
  GROUND_Y,
  JUMP_VELOCITY,
  PLAYER_X,
  PLAYER_WIDTH,
  WORLD_HEIGHT,
  WORLD_WIDTH,
  type RunnerState,
} from "../model/wake-game";
import { nightAmount } from "../model/runner-sky";

export type RunnerPhase = "idle" | "running" | "paused" | "crashed";
export type RunnerPalette = {
  background: string;
  line: string;
  nightBackground: string;
  nightLine: string;
};

// CSS에서 읽은 computed rgb 색을 섞어 디자인 시스템의 역할 색을 그대로 사용한다.
function mixColor(from: string, to: string, amount: number) {
  const start = from.match(/[\d.]+/g)?.map(Number);
  const end = to.match(/[\d.]+/g)?.map(Number);
  if (!start || !end) return amount < 0.5 ? from : to;
  return `rgb(${start
    .slice(0, 3)
    .map((value, index) => Math.round(value + (end[index] - value) * amount))
    .join(", ")})`;
}

export function drawRunner(
  context: CanvasRenderingContext2D,
  atlas: HTMLImageElement,
  state: RunnerState,
  phase: RunnerPhase,
  reducedMotion: boolean,
  palette: RunnerPalette,
) {
  const night = nightAmount(state.elapsed, reducedMotion);
  const background = mixColor(
    palette.background,
    palette.nightBackground,
    night,
  );
  const line = mixColor(palette.line, palette.nightLine, night);
  context.clearRect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
  context.globalAlpha = 1;
  context.fillStyle = background;
  context.fillRect(0, 0, WORLD_WIDTH, WORLD_HEIGHT);
  // 작은 해와 달, 고정된 별만 둔다. 밤에도 캐릭터와 장애물은 원래 색으로 그린다.
  context.globalAlpha = 1 - night;
  context.fillStyle = palette.line;
  context.beginPath();
  context.arc(272, 46, 9, 0, Math.PI * 2);
  context.fill();
  context.globalAlpha = night;
  context.fillStyle = palette.nightLine;
  for (const [x, y, radius] of [
    [34, 26, 1],
    [94, 48, 1.2],
    [160, 22, 1],
    [218, 70, 1],
    [300, 98, 1.2],
  ]) {
    context.beginPath();
    context.arc(x, y, radius, 0, Math.PI * 2);
    context.fill();
  }
  context.beginPath();
  context.arc(272, 46, 10, 0, Math.PI * 2);
  context.fill();
  context.fillStyle = background;
  context.beginPath();
  context.arc(276, 43, 9, 0, Math.PI * 2);
  context.fill();
  context.globalAlpha = 1;
  context.strokeStyle = line;
  context.lineWidth = 0.8;
  context.beginPath();
  context.moveTo(0, GROUND_Y + 1);
  context.lineTo(WORLD_WIDTH, GROUND_Y + 1);
  context.stroke();
  if (!reducedMotion) {
    context.lineWidth = 0.6;
    context.beginPath();
    const offset = state.distance % 42;
    for (let x = -offset; x < WORLD_WIDTH; x += 42) {
      context.moveTo(x, GROUND_Y + 13);
      context.lineTo(x + 8, GROUND_Y + 13);
    }
    context.stroke();
  }

  const cellWidth = atlas.width / 6;
  const cellHeight = atlas.height / 4;
  const sprite = (
    index: number,
    center: number,
    foot: number,
    size: number,
  ) => {
    context.drawImage(
      atlas,
      (index % 6) * cellWidth,
      Math.floor(index / 6) * cellHeight,
      cellWidth,
      cellHeight,
      center - size / 2,
      foot - size * 0.96,
      size,
      size,
    );
  };
  const runFrame = Math.floor(state.elapsed * 10) % 4;
  const companionAge =
    state.jumpAge === null ? 0 : Math.max(0, state.jumpAge - 0.07);
  const companionY = Math.max(
    0,
    JUMP_VELOCITY * companionAge - (GRAVITY * companionAge ** 2) / 2,
  );
  const moving = phase === "running";
  sprite(
    phase === "crashed"
      ? 17
      : companionY > 2
        ? 16
        : moving
          ? 12 + runFrame
          : 12,
    28,
    GROUND_Y - companionY,
    52,
  );
  sprite(
    phase === "crashed"
      ? 8
      : state.y > 2
        ? state.velocity > 0
          ? 4
          : 5
        : moving
          ? runFrame
          : 6,
    PLAYER_X + PLAYER_WIDTH / 2,
    GROUND_Y - state.y,
    72,
  );
  for (const obstacle of state.obstacles) {
    // 생성된 원본(1536×1024)의 실제 물체 경계. 투명 여백을 빼야 보이는 높이와 충돌 높이가 맞는다.
    const source =
      obstacle.kind === "stone" ? [65, 944, 126, 57] : [547, 918, 188, 85];
    const height = obstacle.height;
    const width = (source[2] / source[3]) * height;
    context.drawImage(
      atlas,
      source[0],
      source[1],
      source[2],
      source[3],
      obstacle.x + obstacle.width / 2 - width / 2,
      GROUND_Y - height + 1,
      width,
      height,
    );
  }
}
