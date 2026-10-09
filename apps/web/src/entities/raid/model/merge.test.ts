import { describe, expect, it } from "vitest";

import { mergeRaidAttack, mergeRaidFetch, mergeRaidLive } from "./merge";
import type { RaidAttackResult, RaidLive, RaidState } from "./types";

function state(overrides: Partial<RaidState["boss"] & object> = {}): RaidState {
  return {
    boss: {
      bossId: 7,
      emotion: "ANXIETY",
      maxHp: 300,
      hp: 200,
      status: "ALIVE",
      participantCount: 5,
      spawnedAt: "2026-10-09T00:00:00Z",
      endedAt: null,
      ...overrides,
    },
    myDamage: 3,
    nextBossAt: null,
    available: true,
    epoch: 10,
  };
}

function live(overrides: Partial<RaidLive> = {}): RaidLive {
  return {
    bossId: 7,
    hp: 190,
    maxHp: 300,
    status: "ALIVE",
    participantCount: 6,
    available: true,
    epoch: 10,
    ...overrides,
  };
}

function attack(overrides: Partial<RaidAttackResult> = {}): RaidAttackResult {
  return {
    bossId: 7,
    hp: 199,
    maxHp: 300,
    status: "ALIVE",
    participantCount: 5,
    myDamage: 4,
    defeated: false,
    cooldownMs: 1000,
    ...overrides,
  };
}

describe("mergeRaidLive", () => {
  it("US2-AC1 다른 회원의 공격으로 줄어든 HP와 늘어난 참여자 수를 반영한다", () => {
    const { state: merged, refetch } = mergeRaidLive(state(), live());

    expect(merged?.boss?.hp).toBe(190);
    expect(merged?.boss?.participantCount).toBe(6);
    // 이벤트에는 내 기여가 없다. 그대로 둔다
    expect(merged?.myDamage).toBe(3);
    expect(refetch).toBe(false);
  });

  it("US2-AC2 늦게 온 큰 HP가 화면의 HP를 올리지 않는다", () => {
    const { state: merged } = mergeRaidLive(
      state({ hp: 150, participantCount: 9 }),
      live({ hp: 190, participantCount: 6 }),
    );

    expect(merged?.boss?.hp).toBe(150);
    expect(merged?.boss?.participantCount).toBe(9);
  });

  it("epoch가 커지면 값이 기록에서 다시 채워진 것이라 받은 값을 따른다", () => {
    const { state: merged } = mergeRaidLive(
      state({ hp: 150 }),
      live({ hp: 160, participantCount: 4, epoch: 11 }),
    );

    expect(merged?.boss?.hp).toBe(160);
    expect(merged?.boss?.participantCount).toBe(4);
    expect(merged?.epoch).toBe(11);
  });

  it("US2-AC7 처치 소식이 오면 상태를 바꾸고 결과를 받으러 조회를 다시 한다", () => {
    const { state: merged, refetch } = mergeRaidLive(
      state({ hp: 1 }),
      live({ hp: 0, status: "DEFEATED" }),
    );

    expect(merged?.boss?.status).toBe("DEFEATED");
    expect(merged?.boss?.hp).toBe(0);
    expect(refetch).toBe(true);
  });

  it("끝난 보스는 늦게 온 소식으로 되살아나지 않는다", () => {
    const { state: merged, refetch } = mergeRaidLive(
      state({ hp: 0, status: "DEFEATED" }),
      live({ hp: 3, status: "ALIVE" }),
    );

    expect(merged?.boss?.status).toBe("DEFEATED");
    expect(merged?.boss?.hp).toBe(0);
    expect(refetch).toBe(false);
  });

  it("보스가 바뀌었거나 아직 조회한 적이 없으면 조회를 다시 한다", () => {
    const cached = state();

    expect(mergeRaidLive(cached, live({ bossId: 8 }))).toEqual({
      state: cached,
      refetch: true,
    });
    expect(mergeRaidLive(undefined, live())).toEqual({
      state: undefined,
      refetch: true,
    });
    expect(mergeRaidLive({ ...cached, boss: null }, live()).refetch).toBe(true);
  });

  it("US3-AC6 쉬는 중이라는 소식이 오면 화면의 값을 둔 채 available만 내린다", () => {
    const { state: merged } = mergeRaidLive(
      state({ hp: 150 }),
      live({ hp: 160, available: false }),
    );

    expect(merged?.available).toBe(false);
    expect(merged?.boss?.hp).toBe(150);
  });
});

describe("mergeRaidAttack", () => {
  it("US1-AC2 내 공격의 응답으로 HP와 내 기여를 바꾼다", () => {
    const merged = mergeRaidAttack(state(), attack());

    expect(merged?.boss?.hp).toBe(199);
    expect(merged?.myDamage).toBe(4);
  });

  it("실시간 소식이 먼저 와 있었으면 더 작은 HP를 남긴다", () => {
    const merged = mergeRaidAttack(state({ hp: 180 }), attack({ hp: 199 }));

    expect(merged?.boss?.hp).toBe(180);
    expect(merged?.myDamage).toBe(4);
  });

  it("이 공격으로 처치됐으면 상태가 바뀐다", () => {
    const merged = mergeRaidAttack(
      state({ hp: 1 }),
      attack({ hp: 0, status: "DEFEATED", defeated: true }),
    );

    expect(merged?.boss?.status).toBe("DEFEATED");
  });

  it("다른 보스의 응답은 버린다", () => {
    const cached = state();

    expect(mergeRaidAttack(cached, attack({ bossId: 8 }))).toBe(cached);
    expect(mergeRaidAttack(undefined, attack())).toBeUndefined();
  });
});

describe("mergeRaidFetch", () => {
  it("US2-AC2 조회가 떠난 사이 받은 더 작은 HP를 늦게 온 응답이 덮지 않는다", () => {
    const merged = mergeRaidFetch(
      state({ hp: 150, participantCount: 9 }),
      state({ hp: 170, participantCount: 8 }),
    );

    expect(merged.boss?.hp).toBe(150);
    expect(merged.boss?.participantCount).toBe(9);
  });

  it("처음 받았거나 보스가 바뀌었으면 받은 값을 그대로 쓴다", () => {
    const fetched = state({ bossId: 8, hp: 300 });

    expect(mergeRaidFetch(undefined, fetched)).toBe(fetched);
    expect(mergeRaidFetch(state({ hp: 10 }), fetched)).toBe(fetched);
  });

  it("끝난 보스의 끝난 때와 다음 보스가 나오는 때를 받아 온다", () => {
    const fetched: RaidState = {
      ...state({ hp: 0, status: "DEFEATED", endedAt: "2026-10-09T03:00:00Z" }),
      nextBossAt: "2026-10-09T15:00:00Z",
    };

    const merged = mergeRaidFetch(
      state({ hp: 0, status: "DEFEATED" }),
      fetched,
    );

    expect(merged.boss?.endedAt).toBe("2026-10-09T03:00:00Z");
    expect(merged.nextBossAt).toBe("2026-10-09T15:00:00Z");
  });

  it("US3-AC6 서버가 쉬는 중이면 화면의 값을 두고 available만 내린다", () => {
    const merged = mergeRaidFetch(state({ hp: 150 }), {
      ...state({ hp: 170 }),
      available: false,
      epoch: 0,
    });

    expect(merged.available).toBe(false);
    expect(merged.boss?.hp).toBe(150);
  });

  it("US3-AC8 epoch가 달라졌으면 받은 값을 따른다", () => {
    const fetched = { ...state({ hp: 170 }), epoch: 11 };

    expect(mergeRaidFetch(state({ hp: 150 }), fetched)).toBe(fetched);
  });
});
