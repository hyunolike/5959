import { runRedis, runSql } from "./db";

/**
 * 살아 있는 보스를 물러나게 하고 HP가 [maxHp]인 새 보스를 놓는다. Redis의 보스를 지워 다음 요청이 기록에서
 * 새 보스를 올리게 한다. 보스는 서비스 전체에 하나라, 레이드를 쓰는 테스트는 차례로 돌아야 한다.
 */
export function freshBoss(maxHp: number): number {
  if (!Number.isInteger(maxHp) || maxHp <= 0) {
    throw new Error(`보스의 HP가 올바르지 않습니다: ${maxHp}`);
  }
  runSql(
    "update raid_boss set status = 'RETREATED', ended_at = now() where status = 'ALIVE'",
  );
  const output = runSql(
    "insert into raid_boss (emotion, max_hp, hp, status, spawned_at) " +
      `values ('LETHARGY', ${maxHp}, ${maxHp}, 'ALIVE', now()) returning id`,
  );
  const bossId = Number(output.trim().split("\n")[0]);
  if (!Number.isInteger(bossId)) {
    throw new Error(`보스를 만들지 못했습니다: ${output}`);
  }
  runRedis("DEL", "raid:boss");
  return bossId;
}
