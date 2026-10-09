import type {
  RaidAttackResult,
  RaidBossStatus,
  RaidLive,
  RaidState,
} from "./types";

/**
 * 받은 값을 지금 화면의 레이드에 합친다(006 research R8). 공격 응답, 실시간 이벤트, 조회 응답이 어떤
 * 순서로 와도 HP가 뒤로 돌아가지 않는다.
 *
 * - 같은 보스면 HP는 작은 쪽, 참여자 수와 내 기여는 큰 쪽을 남긴다.
 * - `epoch`가 커졌으면 서버의 값이 기록에서 다시 채워진 것이다. 이때만 받은 값을 그대로 따른다.
 * - 한 번 끝난 보스는 다시 살아나지 않는다.
 */
function mergeBoss(
  cached: RaidState,
  incoming: {
    hp: number;
    participantCount: number;
    status: RaidBossStatus;
    epoch?: number;
  },
): RaidState {
  const boss = cached.boss;
  if (boss === null) {
    return cached;
  }
  const restored =
    incoming.epoch !== undefined && incoming.epoch > cached.epoch;
  return {
    ...cached,
    epoch: restored ? incoming.epoch! : cached.epoch,
    boss: {
      ...boss,
      hp: restored ? incoming.hp : Math.min(boss.hp, incoming.hp),
      participantCount: restored
        ? incoming.participantCount
        : Math.max(boss.participantCount, incoming.participantCount),
      status: boss.status === "ALIVE" ? incoming.status : boss.status,
    },
  };
}

export interface LiveMerge {
  state: RaidState | undefined;
  /**
   * 이벤트만으로는 화면을 채울 수 없어 조회를 다시 해야 한다. 보스가 바뀌었거나(새 보스의 감정을 모른다),
   * 보스가 끝났거나(끝난 때와 다음 보스가 나오는 때를 모른다), 아직 조회한 적이 없을 때다.
   */
  refetch: boolean;
}

/** 실시간 `raid` 이벤트를 합친다. 이벤트에는 내 기여가 없어 그대로 둔다. */
export function mergeRaidLive(
  cached: RaidState | undefined,
  live: RaidLive,
): LiveMerge {
  if (cached === undefined || cached.boss === null) {
    return { state: cached, refetch: true };
  }
  if (live.bossId !== cached.boss.bossId) {
    return { state: cached, refetch: true };
  }
  const merged = mergeBoss(cached, live);
  const state = { ...merged, available: live.available };
  const ended =
    cached.boss.status === "ALIVE" && state.boss?.status !== "ALIVE";
  return { state, refetch: ended };
}

/** 내 공격의 응답을 합친다. 응답을 받았다는 것은 레이드가 열려 있다는 뜻이다. */
export function mergeRaidAttack(
  cached: RaidState | undefined,
  result: RaidAttackResult,
): RaidState | undefined {
  if (
    cached === undefined ||
    cached.boss === null ||
    cached.boss.bossId !== result.bossId
  ) {
    return cached;
  }
  const merged = mergeBoss(cached, result);
  return {
    ...merged,
    available: true,
    myDamage: Math.max(cached.myDamage, result.myDamage),
  };
}

/**
 * 조회 응답을 합친다. 같은 보스이고 `epoch`가 같으면, 조회가 떠난 사이에 실시간으로 받은 더 작은 HP를
 * 조회 응답이 덮지 못하게 한다. 보스가 바뀌었거나 `epoch`가 달라졌으면 받은 값을 그대로 따른다.
 */
export function mergeRaidFetch(
  cached: RaidState | undefined,
  fetched: RaidState,
): RaidState {
  const mine = cached?.boss ?? null;
  const theirs = fetched.boss;
  if (
    cached === undefined ||
    mine === null ||
    theirs === null ||
    mine.bossId !== theirs.bossId
  ) {
    return fetched;
  }
  if (!fetched.available) {
    // 서버가 쉬는 중이면 마지막 기록을 준다. 화면의 값이 더 새롭다.
    return { ...cached, available: false };
  }
  if (fetched.epoch !== cached.epoch) {
    return fetched;
  }
  return {
    ...fetched,
    myDamage: Math.max(cached.myDamage, fetched.myDamage),
    boss: {
      ...theirs,
      hp: Math.min(mine.hp, theirs.hp),
      participantCount: Math.max(
        mine.participantCount,
        theirs.participantCount,
      ),
      status: mine.status === "ALIVE" ? theirs.status : mine.status,
      endedAt: theirs.endedAt ?? mine.endedAt,
    },
  };
}
