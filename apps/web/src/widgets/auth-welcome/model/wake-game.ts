export const WORLD_WIDTH = 320;
export const WORLD_HEIGHT = 220;
export const GROUND_Y = 174;
export const PLAYER_X = 64;
export const PLAYER_WIDTH = 20;
export const JUMP_VELOCITY = 440;
export const GRAVITY = 1_100;
const STEP = 1 / 120;

export type RunnerObstacle = {
  id: number;
  x: number;
  width: number;
  height: number;
  kind: "stone" | "log";
};
export type RunnerState = {
  elapsed: number;
  distance: number;
  y: number;
  velocity: number;
  jumpAge: number | null;
  crashed: boolean;
  obstacles: RunnerObstacle[];
  remainder: number;
  nextSpawn: number;
  spawnId: number;
  seed: number;
};

export function createRunner(seed = 1): RunnerState {
  return {
    elapsed: 0,
    distance: 0,
    y: 0,
    velocity: 0,
    jumpAge: null,
    crashed: false,
    obstacles: [],
    remainder: 0,
    nextSpawn: 5,
    spawnId: 0,
    seed: seed >>> 0,
  };
}

export function runnerSpeed(elapsed: number) {
  return 90 + Math.min(4, Math.floor(Math.max(0, elapsed) / 15)) * 10;
}

export function jumpRunner(state: RunnerState): RunnerState {
  if (state.crashed || state.y > 0 || state.velocity > 0) return state;
  return { ...state, velocity: JUMP_VELOCITY, jumpAge: 0 };
}

/** 120Hz로 시간을 분할한다. 프레임마다 다른 적분 간격을 쓰지 않는다. */
export function stepRunner(state: RunnerState, seconds: number): RunnerState {
  if (state.crashed || !Number.isFinite(seconds) || seconds <= 0) return state;
  const total = state.remainder + seconds;
  const steps = Math.floor((total + 1e-10) / STEP);
  const next: RunnerState = {
    ...state,
    obstacles: state.obstacles.map((obstacle) => ({ ...obstacle })),
    remainder: Math.max(0, total - steps * STEP),
  };

  for (let i = 0; i < steps; i++) {
    const travel = runnerSpeed(next.elapsed) * STEP;
    next.elapsed += STEP;
    next.distance += travel;
    if (next.jumpAge !== null) {
      next.jumpAge += STEP;
      if (next.jumpAge > 0.9) next.jumpAge = null;
    }
    if (next.velocity !== 0 || next.y > 0) {
      next.y = Math.max(
        0,
        next.y + next.velocity * STEP - (GRAVITY * STEP * STEP) / 2,
      );
      next.velocity = next.y === 0 ? 0 : next.velocity - GRAVITY * STEP;
    }
    for (const obstacle of next.obstacles) obstacle.x -= travel;
    next.obstacles = next.obstacles.filter(
      (obstacle) => obstacle.x + obstacle.width > -16,
    );
    if (next.elapsed + 1e-9 >= next.nextSpawn) {
      const kind =
        next.spawnId >= 3 && next.spawnId % 3 === 0 ? "log" : "stone";
      next.obstacles.push({
        id: next.spawnId++,
        x: WORLD_WIDTH + 8,
        width: kind === "stone" ? 18 : 26,
        height: kind === "stone" ? 19 : 24,
        kind,
      });
      next.seed = (Math.imul(next.seed, 1664525) + 1013904223) >>> 0;
      next.nextSpawn += 2.2 + (next.seed / 0x1_0000_0000) * 0.9;
    }
    if (
      next.obstacles.some(
        (obstacle) =>
          obstacle.x < PLAYER_X + PLAYER_WIDTH &&
          obstacle.x + obstacle.width > PLAYER_X &&
          next.y < obstacle.height - 3,
      )
    ) {
      next.crashed = true;
      next.velocity = 0;
      next.y = 0;
      next.jumpAge = null;
      next.remainder = 0;
      break;
    }
  }
  return next;
}
