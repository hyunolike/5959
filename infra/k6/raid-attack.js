// 보스 레이드 부하 테스트(006 research R15, SC-001~SC-003).
// 가상 사용자마다 회원 하나를 맡아 1초에 한 번씩 보스를 공격한다. API를 직접 부른다(BFF를 거치지 않는다).
//
// 직접 돌리지 말고 infra/k6/run-raid-load.sh로 돌린다. 그 스크립트가 보스를 놓고, 끝난 뒤 받아들여진 공격 수를
// Redis와 Postgres의 값과 견준다. 운영 환경에는 돌리지 않는다.
//
//   k6 run -e API=http://localhost:8080 -e VUS=500 -e DURATION=60s infra/k6/raid-attack.js
import http from "k6/http";
import { sleep } from "k6";
import { Counter, Gauge, Trend } from "k6/metrics";

const API = __ENV.API || "http://localhost:8080";
const VUS = Number(__ENV.VUS || 500);
const DURATION = __ENV.DURATION || "60s";
const PASSWORD = "abcd1234";
// 쿨다운(1초)보다 조금 길게 쉬어, 쿨다운에 걸려 버려지는 요청을 줄인다.
const INTERVAL_SECONDS = Number(__ENV.INTERVAL_SECONDS || 1.05);
const SETUP_BATCH = 25;
const JSON_HEADERS = { "Content-Type": "application/json" };

const accepted = new Counter("raid_accepted");
const cooldown = new Counter("raid_cooldown");
const ended = new Counter("raid_ended");
const failed = new Counter("raid_failed");
const attackDuration = new Trend("raid_attack_duration", true);
const finalHp = new Gauge("raid_final_hp");
const maxHp = new Gauge("raid_max_hp");
const bossIdGauge = new Gauge("raid_boss_id");

export const options = {
  setupTimeout: "10m",
  teardownTimeout: "1m",
  scenarios: {
    attack: {
      executor: "constant-vus",
      vus: VUS,
      duration: DURATION,
      gracefulStop: "5s",
    },
  },
  thresholds: {
    // SC-003: 공격 응답의 95%가 300ms 안
    raid_attack_duration: ["p(95)<300"],
    raid_failed: ["count==0"],
  },
  summaryTrendStats: ["avg", "min", "med", "p(90)", "p(95)", "p(99)", "max"],
};

function post(path, body, token) {
  const headers = token
    ? Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS)
    : JSON_HEADERS;
  return { method: "POST", url: `${API}${path}`, body: JSON.stringify(body), params: { headers } };
}

function requireStatus(responses, status, step) {
  responses.forEach((response) => {
    if (response.status !== status) {
      throw new Error(`${step} 실패: ${response.status} ${response.body}`);
    }
  });
}

/** 회원 VUS명을 가입, 온보딩, 로그인까지 마치고 토큰을 모은다. */
export function setup() {
  const run = Date.now().toString(36);
  const tokens = [];
  for (let start = 0; start < VUS; start += SETUP_BATCH) {
    const indexes = [];
    for (let i = start; i < Math.min(start + SETUP_BATCH, VUS); i += 1) {
      indexes.push(i);
    }
    const email = (i) => `raidload-${run}-${i}@example.com`;
    const signups = http.batch(
      indexes.map((i) => post("/api/v1/auth/signup", { email: email(i), password: PASSWORD })),
    );
    signups.forEach((response) => {
      if (response.status !== 200 && response.status !== 201) {
        throw new Error(`가입 실패: ${response.status} ${response.body}`);
      }
    });
    const onboardings = http.batch(
      indexes.map((i, at) => ({
        method: "PUT",
        url: `${API}/api/v1/members/me/onboarding`,
        body: JSON.stringify({
          // 닉네임은 10자까지다
          nickname: `r${run.slice(-5)}${i.toString(36)}`.slice(0, 10),
          jobRole: "DESIGN",
          careerYear: "YEAR_3",
        }),
        params: {
          headers: Object.assign(
            { Authorization: `Bearer ${signups[at].json("data.tokens.accessToken")}` },
            JSON_HEADERS,
          ),
        },
      })),
    );
    requireStatus(onboardings, 200, "온보딩");
    // 온보딩을 마친 토큰은 다시 로그인해야 받는다
    const logins = http.batch(
      indexes.map((i) => post("/api/v1/auth/login", { email: email(i), password: PASSWORD })),
    );
    requireStatus(logins, 200, "로그인");
    logins.forEach((response) => tokens.push(response.json("data.tokens.accessToken")));
  }

  const raid = http.get(`${API}/api/v1/raid`, { headers: { Authorization: `Bearer ${tokens[0]}` } });
  const boss = raid.json("data.boss");
  if (raid.status !== 200 || !boss || boss.status !== "ALIVE") {
    throw new Error(`살아 있는 보스가 없습니다: ${raid.status} ${raid.body}`);
  }
  return { tokens, bossId: boss.bossId, maxHp: boss.maxHp };
}

export default function attack(data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  const started = Date.now();
  const response = http.post(
    `${API}/api/v1/raid/attacks`,
    JSON.stringify({ bossId: data.bossId }),
    { headers: Object.assign({ Authorization: `Bearer ${token}` }, JSON_HEADERS), tags: { name: "attack" } },
  );
  attackDuration.add(response.timings.duration);
  if (response.status === 200) {
    accepted.add(1);
  } else if (response.status === 429) {
    cooldown.add(1);
  } else if (response.status === 409) {
    ended.add(1);
  } else {
    failed.add(1);
  }
  const elapsed = (Date.now() - started) / 1000;
  sleep(Math.max(0, INTERVAL_SECONDS - elapsed));
}

/** 끝난 뒤의 HP를 남긴다. 받아들여진 수와 견주는 일은 handleSummary와 run-raid-load.sh가 한다. */
export function teardown(data) {
  const raid = http.get(`${API}/api/v1/raid`, { headers: { Authorization: `Bearer ${data.tokens[0]}` } });
  const boss = raid.json("data.boss");
  bossIdGauge.add(data.bossId);
  maxHp.add(data.maxHp);
  // 보스가 처치돼 다른 보스로 바뀌는 일은 다음 날 0시 전에는 없다
  finalHp.add(boss && boss.bossId === data.bossId ? boss.hp : -1);
}

function count(data, name) {
  const metric = data.metrics[name];
  return metric ? metric.values.count : 0;
}

export function handleSummary(data) {
  const duration = data.metrics.raid_attack_duration.values;
  const result = {
    bossId: data.metrics.raid_boss_id.values.value,
    vus: VUS,
    duration: DURATION,
    accepted: count(data, "raid_accepted"),
    cooldown: count(data, "raid_cooldown"),
    ended: count(data, "raid_ended"),
    failed: count(data, "raid_failed"),
    maxHp: data.metrics.raid_max_hp.values.value,
    finalHp: data.metrics.raid_final_hp.values.value,
    attackMs: {
      avg: duration.avg,
      med: duration.med,
      p90: duration["p(90)"],
      p95: duration["p(95)"],
      p99: duration["p(99)"],
      max: duration.max,
    },
  };
  result.hpLost = result.maxHp - result.finalHp;
  result.hpMatchesAccepted = result.hpLost === result.accepted;
  const lines = [
    "",
    `보스 ${result.bossId}: 가상 사용자 ${VUS}명, ${DURATION}`,
    `받아들여진 공격 ${result.accepted}건 (쿨다운 ${result.cooldown}, 끝난 뒤 ${result.ended}, 실패 ${result.failed})`,
    `줄어든 HP ${result.hpLost} (${result.maxHp} → ${result.finalHp}) ${result.hpMatchesAccepted ? "= 받아들여진 수" : "≠ 받아들여진 수 !!!"}`,
    `공격 응답 ms: 평균 ${duration.avg.toFixed(1)}, p50 ${duration.med.toFixed(1)}, p95 ${duration["p(95)"].toFixed(1)}, p99 ${duration["p(99)"].toFixed(1)}, 최대 ${duration.max.toFixed(1)}`,
    "",
  ];
  const outputs = { stdout: lines.join("\n") };
  if (__ENV.SUMMARY_FILE) {
    outputs[__ENV.SUMMARY_FILE] = JSON.stringify(result, null, 2);
  }
  return outputs;
}
