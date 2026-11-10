# Data Model: 무료 인프라로 이전 (018-free-infra)

회원이 보는 데이터의 모양은 바뀌지 않는다. 테이블 하나를 더하고, 메모리에만 있는 상태 셋을 새로 정한다. 지금 Redis에 있던 것은 메모리로 옮긴다.

## 1. 새 테이블: `embedding_backfill` (V16)

`EmbeddingBackfill`이 기동할 때마다 모든 글을 다시 훑지 않게 하는 표지(research R3).

| 열 | 형 | 규칙 |
|---|---|---|
| `model` | `VARCHAR(100)` PK | `Embedder.model`의 이름 |
| `completed_at` | `TIMESTAMPTZ NOT NULL` | 그 모델로 빠진 글 채우기와 다른 모델 값 다시 요청을 마친 때 |

- 기동 때 지금 모델의 행이 있으면 훑지 않는다. 없으면 지금처럼 훑고, 끝나면 `INSERT ... ON CONFLICT DO NOTHING`.
- 모델을 바꾸면 새 이름의 행이 없으므로 한 번 훑는다. 지금의 "모델을 바꾸면 옛 값을 차례로 다시 만든다"가 그대로다.
- 훑는 도중 죽으면 행이 없어 다음 기동 때 처음부터 다시 훑는다. 훑기는 이미 있는 행을 건너뛰므로(`existingIds`) 결과는 같다.
- 글이 새로 생기는 길(`EmbeddingListener`)은 이 표지와 상관없다.

## 2. 메모리 상태: 다음 할 일의 시각 (`DueTimer`)

작업마다 하나다. 저장하지 않는다. 기동 때와 매일 04:00에 DB에서 다시 계산한다.

| 작업 | 다음 시각을 정하는 쿼리 | 다시 거는 곳(커밋 뒤) |
|---|---|---|
| 감정 분석 재시도 | `min(next_attempt_at) from emotion_analysis where status = 'PENDING'` | 행 생성, 맡기(임대), 실패(백오프) |
| 위험 분류 재시도 | `min(next_attempt_at) from risk_assessment where status = 'PENDING'` | 같음 |
| 임베딩 재시도 | `min(next_attempt_at) from post_embedding where status = 'PENDING'` | 같음, 백필의 다시 요청 |
| 주간 편지 재시도 | `min(letter_next_attempt_at) from weekly_report where letter_status = 'PENDING'` | 리포트 생성, 실패 |
| AI 친구 반응 | `min(greatest(post_created_at + wait, next_attempt_at)) from companion_reaction where status = 'WAITING'`, 그리고 가장 이른 만료(`post_created_at + 24h`) | 행 생성, 뒤따르는 친구 예약, 미루기 |
| AI 친구 글쓰기 | 쓰는 중인 행의 `next_attempt_at`, 없으면 `max(가장 새 글 + 6h, 오늘 또는 내일 08:00)`. 23:00을 넘으면 다음 날 08:00 | 글 생성(어느 글이든 조용한 시간을 미룬다), 행 열기 |
| 레이드 보스 교체 | 살아 있는 보스면 `spawned_at + 7d`, 끝났으면 `ended_at` 다음 날 00:00(한국 시간) | 보스 생성, 처치, 물러나기 |
| 주간 리포트 | 이번 주 월요일 05:00(한국 시간). `weekly_report_run`이 있으면 다음 주 월요일 | 한 주를 다 훑었을 때 |
| 하루 정리 | 매일 04:00(한국 시간) 고정 | - |

- 규칙: `arm(at)`은 지금 걸린 시각보다 이를 때만 바꾼다. 울리면 그 작업을 돌리고 다음 시각을 다시 읽는다. 기능이 꺼져 있으면(`ogu.companion.enabled=false` 등) 걸지 않는다. 테스트 스위치(`*.scheduler-enabled=false`)도 같다.
- 지난 시각이 걸리면 바로 돈다. 서버가 잠들었다 깨어난 뒤 밀린 일이 이렇게 돈다(US4-AC4).

## 3. 메모리 상태: 회원 요청의 때 (`ActivityGate`)

| 값 | 뜻 |
|---|---|
| `lastActivityAt` | 마지막 `/api/v1/**` 요청 또는 커밋 뒤 이벤트 리스너가 돈 때 |

- `isActive(now) = now − lastActivityAt < 4분`. 이벤트 재전송(1분)과 알림 안전망(60초)은 활성일 때만 돈다.
- 헬스 체크, 주기 작업, 타이머 자신은 갱신하지 않는다.

## 4. 메모리 상태: 레이드 (`RaidLiveStore`)

지금 Redis의 키(`raid:boss`, `raid:contrib:{bossId}`, `raid:dirty:{bossId}`, `raid:cd:{memberId}`, `raid:load-lock`)를 옮긴 것이다. 잠금 하나(`ReentrantLock`) 안에서만 읽고 쓴다.

| 필드 | 형 | 옛 자리 |
|---|---|---|
| `boss` | `RaidBoss?`(`id, emotion, maxHp, hp, status, spawnedAt, endedAt, epoch`) | `raid:boss` 해시 |
| `contributions` | `Map<memberId, damage>` | `raid:contrib:{bossId}` |
| `dirty` | `Set<memberId>` | `raid:dirty:{bossId}` |
| `cooldownUntil` | `Map<memberId, Instant>` | `raid:cd:{memberId}`(PX TTL) |
| `accepting` | `Boolean` | 없음(새로). 종료 중이면 false |

**불변식**(지금과 같다): 받아들인 공격 수 = `maxHp − hp` = `contributions`의 합. `hp`는 0에서 멈춘다. `status`가 `ALIVE`가 아니면 공격을 받지 않는다. 처치(`hp = 0`)는 한 번만 `DEFEATED`로 바뀐다.

**상태 바뀜**:
- 비어 있음 → 올림: 첫 레이드 요청이나 보스 교체 때 `raid_boss`의 최신 행과 `raid_contribution`으로 채운다. 살아 있는 보스면 `hp = max(1, maxHp − 기여의 합)`. `epoch = 올린 시각(ms)`.
- 맞추기(기동 뒤 3분, 30초마다): 회원별 `max(메모리, 기록)`, `hp`를 다시 계산(research R6).
- `ALIVE` → `DEFEATED`: 공격으로 `hp`가 0이 될 때(잠금 안).
- `ALIVE` → `RETREATED`: 보스 교체 타이머가 `spawned_at + 7d`에 끝낸다.
- 쿨다운 맵은 만료된 것을 기록할 때 함께 지운다. 끝난 보스의 기여와 바뀐 회원은 다음 보스를 올릴 때 비운다.

`raid_boss`, `raid_contribution`의 모양은 바뀌지 않는다.

## 5. 지우는 것

- 설정: `spring.data.redis.*`, `management.endpoint.health.group.realtime`, `ogu.notification.outage-drain-interval`, `ogu.notification.probe-interval`.
- 의존: `spring-boot-starter-data-redis`, `com.redis:testcontainers-redis`.
- 자원 파일: `src/main/resources/redis/raid-*.lua` 다섯 개.

## 6. 설정 (새로, 바뀜)

| 키 | 값 | 까닭 |
|---|---|---|
| `spring.datasource.hikari.minimum-idle` | `0` | R2 |
| `spring.datasource.hikari.idle-timeout` | `60s` | R2 |
| `spring.datasource.hikari.keepalive-time` | `0` | R2 |
| `spring.datasource.hikari.maximum-pool-size` | `5` | VM의 값을 옮김 |
| `spring.datasource.hikari.connection-timeout` | `15s` | 잠든 DB가 깨는 시간 |
| `management.endpoint.health.probes.enabled` | `true` | R8, liveness |
| `ogu.notification.connection-lifetime` | `15m` → `50m` | R4 |
| `ogu.maintenance.read-only` | `false` (env `OGU_MAINTENANCE_READ_ONLY`) | R11 |
| `ogu.raid.reconcile-window` | `3m`, `ogu.raid.reconcile-interval` `30s` | R6 |
| `ogu.housekeeping.cron` | `0 0 4 * * *` Asia/Seoul | R3, 정리 여섯 개를 묶음. 옛 cron 키 여섯 개는 지운다 |
| `ogu.activity.window` | `4m` | R3 |
| `server.tomcat.threads.max` | `40` | R1 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `20s` | R5 |
