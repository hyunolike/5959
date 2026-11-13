import { describe, expect, it } from "vitest";

import {
  createRunner,
  jumpRunner,
  runnerSpeed,
  stepRunner,
  WORLD_WIDTH,
  PLAYER_X,
  PLAYER_WIDTH,
} from "./wake-game";

describe("마음 산책 물리", () => {
  it("점프는 한 번만 받아들이고 일정한 높이로 뛰어 원래 바닥에 착지한다", () => {
    const start = jumpRunner(createRunner());
    const rising = stepRunner(start, 0.15);
    expect(rising.y).toBeGreaterThan(40);
    expect(jumpRunner(rising)).toBe(rising);
    const landed = stepRunner(rising, 0.7);
    expect(landed.y).toBe(0);
    expect(landed.velocity).toBe(0);
    expect(jumpRunner(landed).velocity).toBeGreaterThan(0);
  });

  it("첫 5초는 비워 두고 그 뒤 등장하는 장애물은 최소 1.2초의 접근 시간을 준다", () => {
    const practice = stepRunner(createRunner(), 4.99);
    expect(practice.obstacles).toHaveLength(0);
    const started = stepRunner(practice, 0.03);
    expect(started.obstacles).toHaveLength(1);
    expect(
      (WORLD_WIDTH - PLAYER_X - PLAYER_WIDTH) / runnerSpeed(300),
    ).toBeGreaterThanOrEqual(1.2);
  });

  it("충돌하면 판과 이동 거리를 멈추고 충돌한 판은 점프로 재개하지 않는다", () => {
    const crashed = stepRunner(createRunner(), 12);
    expect(crashed.crashed).toBe(true);
    expect(crashed.elapsed).toBeLessThan(12);
    expect(crashed.distance).toBeGreaterThan(0);
    expect(stepRunner(crashed, 5)).toBe(crashed);
    expect(jumpRunner(crashed)).toBe(crashed);
  });

  it("장애물 앞에서 맞춰 점프하면 같은 장애물을 부딪히지 않고 통과한다", () => {
    let state = createRunner();
    while (!state.obstacles.length || state.obstacles[0].x > PLAYER_X + 40)
      state = stepRunner(state, 1 / 120);
    const passed = stepRunner(jumpRunner(state), 0.9);
    expect(passed.crashed).toBe(false);
    expect(passed.obstacles[0].x + passed.obstacles[0].width).toBeLessThan(
      PLAYER_X,
    );
  });

  it("60초 이후 속도를 유지하며 시작과 최대 속도 사이에는 차이가 있다", () => {
    expect(runnerSpeed(0)).toBeLessThan(runnerSpeed(30));
    expect(runnerSpeed(60)).toBe(runnerSpeed(600));
  });

  it("점프에서 내려오다 부딪혀도 동료가 공중에서 멈추지 않게 점프 상태를 끝낸다", () => {
    const landing = {
      ...createRunner(),
      y: 5,
      velocity: -20,
      jumpAge: 0.78,
      obstacles: [
        { id: 0, x: PLAYER_X, width: 18, height: 19, kind: "stone" as const },
      ],
    };
    const crashed = stepRunner(landing, 1 / 120);
    expect(crashed.crashed).toBe(true);
    expect(crashed.jumpAge).toBeNull();
  });

  it("같은 시간을 여러 프레임으로 나눠도 점프 위치와 이동 거리가 같다", () => {
    const single = stepRunner(jumpRunner(createRunner()), 0.5);
    let frames = jumpRunner(createRunner());
    for (let i = 0; i < 30; i++) frames = stepRunner(frames, 1 / 60);
    expect(frames.y).toBeCloseTo(single.y, 8);
    expect(frames.distance).toBeCloseTo(single.distance, 8);
  });

  it("장애물 간격은 연속으로 점프하고 착지할 시간을 보장한다", () => {
    let state = createRunner(42);
    const spawns: number[] = [];
    let lastId = -1;
    for (let i = 0; i < 120 * 80 && !state.crashed; i++) {
      const next = state.obstacles.find(
        (obstacle) => obstacle.x > PLAYER_X + PLAYER_WIDTH,
      );
      if (next && next.x < PLAYER_X + 40 && state.y === 0)
        state = jumpRunner(state);
      state = stepRunner(state, 1 / 120);
      const newest = state.obstacles.at(-1);
      if (newest && newest.id !== lastId) {
        spawns.push(state.elapsed);
        lastId = newest.id;
      }
    }
    expect(state.crashed).toBe(false);
    expect(spawns.length).toBeGreaterThan(20);
    for (let i = 1; i < spawns.length; i++)
      expect(spawns[i] - spawns[i - 1]).toBeGreaterThan(2);
  });
});
