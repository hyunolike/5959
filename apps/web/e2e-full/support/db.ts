import { execFileSync } from "node:child_process";
import path from "node:path";

/**
 * e2e가 DB와 Redis를 직접 만져야 할 때 쓴다(운영자 지정, 레이드 보스 놓기). 둘 다 `infra/compose.e2e.yaml`의
 * 컨테이너 안에서 명령을 돌린다(호스트로 여는 포트가 없다). 다르게 띄웠으면 환경 변수로 명령을 준다.
 *
 * - `E2E_PSQL`: psql까지의 명령(예: `docker exec -i my-pg psql -U ogu -d ogu`)
 * - `E2E_REDIS_CLI`: redis-cli까지의 명령(예: `docker exec -i my-redis redis-cli`)
 */
function composeExec(service: string, ...command: string[]): string[] {
  const composeFile = path.resolve(
    process.cwd(),
    "../../infra/compose.e2e.yaml",
  );
  return [
    "docker",
    "compose",
    "-f",
    composeFile,
    "exec",
    "-T",
    service,
    ...command,
  ];
}

function commandFrom(env: string | undefined, fallback: string[]): string[] {
  return env ? env.split(" ").filter(Boolean) : fallback;
}

/** SQL 한 문장을 돌리고 psql의 출력을 돌려준다. 실패하면 던진다. */
export function runSql(sql: string): string {
  const [command, ...args] = commandFrom(
    process.env.E2E_PSQL,
    composeExec("postgres", "psql", "-U", "ogu", "-d", "ogu"),
  );
  return execFileSync(
    command,
    [...args, "-v", "ON_ERROR_STOP=1", "-At", "-c", sql],
    { encoding: "utf8" },
  );
}

/** redis-cli 명령 하나를 돌린다. */
export function runRedis(...redisArgs: string[]): string {
  const [command, ...args] = commandFrom(
    process.env.E2E_REDIS_CLI,
    composeExec("redis", "redis-cli"),
  );
  return execFileSync(command, [...args, ...redisArgs], { encoding: "utf8" });
}
