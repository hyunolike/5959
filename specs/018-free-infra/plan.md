# Implementation Plan: 무료 인프라로 이전

**Branch**: `018-free-infra` | **Date**: 2026-11-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/018-free-infra/spec.md`

## Summary

API를 GCP VM에서 Render 무료 웹 서비스(싱가포르, 512MB, CPU 0.1개, 한 대)로, DB를 Neon 무료(싱가포르, 5분 쉬면 잠듦, 월 100 CU-시간)로 옮긴다. 웹은 Cloud Run에 남는다. 월 약 $18이 0원이 된다.

DB가 잠들 수 있게 하는 것이 일의 중심이다. 지금은 레이드 기록이 1초마다, 재시도 네 개가 10초마다, 그 밖의 작업이 1분마다 DB를 열고, 연결 풀도 2분마다 확인 쿼리를 보낸다. 주기 작업을 "다음 할 일 시각에만 깨는 타이머"와 "회원이 쓰는 동안만 도는 주기"로 바꾸고, 연결 풀은 쉬는 연결을 두지 않는다. 서버가 한 대가 되므로 Redis를 지운다. 알림 신호는 서버 안에서 바로 전하고, 레이드 판정은 Lua 스크립트에서 메모리의 잠금 하나로 옮긴다. JVM은 JDK 25와 AOT 캐시, 작은 힙으로 512MB 안에 묶는다. 메일은 SMTP 포트만 2587로 바꾼다. 이전은 읽기 전용 점검 한 시간 안에 덤프와 복원으로 한다. 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, **JDK 25**(21에서 올림, research R1) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1, HikariCP 7. **지움**: `spring-boot-starter-data-redis`, `testcontainers-redis` / Next.js 16 (웹은 점검 안내만 더함)

**Storage**: PostgreSQL 17 + pgvector 0.8(Neon, 직접 연결). 마이그레이션 `V16__embedding_backfill.sql`. Redis 없음. 백업은 Cloudflare R2

**Testing**: JUnit 5, Testcontainers(Postgres만), MockMvc, k6(레이드) / Vitest, Playwright. 공급자 동작은 quickstart Q1~Q7

**Target Platform**: Render 무료(Docker 이미지, `linux/amd64`, GHCR에서 받음), Neon 무료, Cloud Run(웹), GitHub Actions(빌드, 배포 훅, 매일 백업과 점검)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web` + `infra`)

**Performance Goals**: 피드 p95가 지금 운영 이하(SC-004), 잠든 DB의 첫 요청 3초 안, 레이드 50명 p95 500ms 안(SC-010), 알림 99%가 5초 안(SC-006)

**Constraints**:
- 컨테이너 메모리 512MB, CPU 0.1개, 15분 요청이 없으면 잠듦(외부 깨우기 10분), 아무 때나 재시작, 로컬 디스크 없음, 메일 포트 465와 587 막힘
- Neon: 5분 동안 쿼리가 없어야 잠듦, 월 100 CU-시간(목표 80 이하), 저장 1GB, 송신 5GB
- 회원이 보는 기능과 API 계약은 바꾸지 않는다. 더하는 것은 점검 응답 하나다
- 모듈 경계 검사(`ModularityTests`)와 지금의 테스트를 느슨하게 하지 않는다. Redis에 기대던 테스트는 같은 약속을 새 구현으로 보는 테스트로 바꾼다

**Scale/Scope**: 인수 조건 38개(US1 6, US2 6, US3 8, US4 8, US5 3, US6 4, US7 3). `@Scheduled` 17개를 모두 바꾸거나 묶는다(타이머 8, 활동 중에만 1, 바뀐 것만 1, Redis와 함께 지움 1, 04:00 하나로 6). Redis에 닿는 클래스 9개와 Lua 5개를 지우거나 바꾼다. Redis 전용 테스트 13개를 지우거나 바꾸고, Redis를 거치는 테스트 약 20개의 지원 코드를 바꾼다. 인프라는 `render.yaml`, 워크플로 둘(배포, 매일), Terraform(웹 env, 정리 정책, VM 정리)

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 틀(`DueTimer`, `ActivityGate`)은 `shared/schedule`에 둔다(`shared`는 OPEN). 모듈 사이 새 의존이 없다. `notification`의 발행기와 `raid`의 저장소는 모듈 안에서 바뀐다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/maintenance.openapi.yaml](contracts/maintenance.openapi.yaml)을 루트에 먼저 합친다(0.17.0). 레이드의 `available`, `epoch`는 그대로 둔다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 38개에 ID가 있다. 공급자 동작과 한 달 사용량(US2-AC1, AC2, US6-AC1, US7)은 quickstart로 본다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위험 분류 재시도는 타이머로 바뀌어도 일정과 24시간 대체 경로가 같다(FR-016). 점검 중에도 위기 글을 막는 키워드 판정은 저장과 같은 트랜잭션이다(점검 중에는 저장 자체가 없다) | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI 호출은 여전히 커밋 뒤다. 재시도 일정이 그대로다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 이 기능의 목적이다. ADR-0004를 대체하는 ADR-0007을 쓴다(FR-024). 되돌리기용 VM 7일의 비용(약 $4)은 ADR에 적는다 | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

**Phase 1 뒤 다시 본 것**: 계약 조각은 응답 하나를 더할 뿐 기존 스키마를 바꾸지 않는다. data-model의 새 테이블은 `recommend` 모듈 안에 있다. 판정이 바뀌지 않는다.

## Project Structure

### Documentation (this feature)

```text
specs/018-free-infra/
├── plan.md
├── research.md          # R1~R12
├── data-model.md        # V16, 메모리 상태 셋, 설정
├── quickstart.md        # Q1~Q7, 전환, 정리
├── contracts/maintenance.openapi.yaml
└── tasks.md             # speckit-tasks가 만든다
```

### Source Code (repository root)

```text
apps/api/
├── build.gradle.kts                         # JDK 25, Redis 의존 지움
├── Dockerfile                               # temurin 25, AOT 캐시 학습, JVM 플래그(R1)
├── compose.yaml                             # redis 서비스 지움
└── src/main/
    ├── resources/application.yml            # Hikari, liveness, 수명 50m, 점검, 정리 cron 하나(data-model 6)
    ├── resources/db/migration/V16__embedding_backfill.sql
    ├── resources/redis/                     # 지움
    └── kotlin/com/ogu/
        ├── shared/schedule/DueTimer.kt, ActivityGate.kt, ActivityFilter.kt, Housekeeping.kt
        ├── shared/config/                   # RedisClientConfig, RedisHealthGroupConfig 지움.
        │                                    # EventPublicationResubmitter는 ActivityGate를 탄다
        ├── shared/maintenance/ReadOnlyFilter.kt, MaintenanceProperties.kt
        ├── shared/error/ErrorCode.kt        # SERVICE_MAINTENANCE
        ├── member/infrastructure/config/ProdAuthSettingsCheck.kt   # REDIS_URL 검사 지움
        ├── member/infrastructure/security/SecurityPaths.kt         # liveness 공개, realtime 지움
        ├── member/application/*CleanupJob.kt                       # Housekeeping의 단계로
        ├── notification/stream/LocalSignalPublisher.kt             # Redis 발행, 구독, 상태, 프로브 지움
        ├── notification/stream/SafetyDrain.kt                      # ActivityGate, 장애 주기 지움
        ├── notification/application/NotificationPurgeJob.kt        # Housekeeping의 단계로
        ├── raid/domain/RaidLiveStore.kt                            # RaidRedis 대신
        ├── raid/application/                                       # Flusher(바뀐 것만), Loader(맞추기),
        │                                                           # Lifecycle(DueTimer), 종료 때 쓰기
        ├── emotion|safety|recommend|report/application/*Retry*      # DueTimer
        ├── recommend/application/EmbeddingBackfill.kt              # 완료 표지
        ├── companion/application/ReactionRunner.kt, PostingRunner.kt  # DueTimer
        └── report/application/WeeklyReportJob.kt, WeeklyReportQueryService.kt  # DueTimer, Housekeeping
apps/api/src/test/kotlin/com/ogu/
├── TestcontainersConfiguration.kt           # Redis 컨테이너 지움
├── support/RaidFixture.kt, notification/NotificationTestSupport.kt   # 메모리 저장소와 발행기로
├── shared/schedule/                         # DueTimer, ActivityGate, Housekeeping 테스트(US4)
├── raid/                                    # RaidScriptTests → RaidLiveStoreTests, 종료 때 쓰기, 맞추기(US3)
└── notification/                            # 두 서버, Redis 장애 테스트 지움. 발행기 테스트 옮김
apps/web/src/
├── shared/api/                              # SERVICE_MAINTENANCE를 점검 안내로
├── shared/api/generated.ts                  # 0.17.0
└── e2e-full/support/raid.ts, db.ts          # redis-cli 대신 기록에서 다시 올리는 길
contracts/openapi.yaml                        # 0.17.0
render.yaml                                   # 새로(R9)
.github/workflows/release.yml                 # GHCR amd64 + 배포 훅
.github/workflows/ops-daily.yml               # 새로: 백업, 사용량 점검, 복구 연습(R10)
.github/workflows/ci.yml                      # 스모크와 e2e에서 Redis 지움, JDK 25
infra/compose.e2e.yaml                        # redis 지움
infra/k6/run-raid-load.sh                     # redis-cli 대신 API와 DB로 불변식
infra/gcp/terraform/web.tf, main.tf           # API_ORIGIN, SSE_PUBLIC_ORIGIN, 정리 정책, (정리 때) VM 지움
infra/RESTORE.md                              # 새 절차
docs/adr/0007-render-neon.md                  # ADR-0004 대체
apps/api/AGENTS.md, README.md                 # Redis 절, 운영 절 고침
```

**Structure Decision**: 지금의 모노레포 구조를 그대로 쓴다. 새 디렉터리는 `shared/schedule`, `shared/maintenance` 둘이다. 옛 GCP VM 자료(`infra/gcp/vm`, `infra/scripts`, `infra/compose.prod.yaml`)는 정리 단계(US7)까지 남기고, 정리 때 지운다. 되돌리기(R12)에 쓰인다.

## 구현 순서

PR을 묶음마다 하나씩 낸다. 묶음 1~5는 지금의 GCP에서도 돌아간다(Redis를 지우는 묶음 3부터는 VM의 compose에서도 Redis가 필요 없어진다). 회원에게 보이는 변화가 없어 하나씩 내보내며 확인한다.

| 묶음 | 내용 | 스토리 | 확인 |
|---|---|---|---|
| 1 | 계약 0.17.0, 웹 타입, 점검 모드(API 필터, 웹 안내), liveness, Hikari 설정 | US1-AC6, US2-AC3 | 테스트, Q2 일부 |
| 2 | `shared/schedule`(DueTimer, ActivityGate, Housekeeping), 주기 작업 12개 옮김, 백필 표지(V16) | US4 | 테스트, 기존 재시도 테스트 |
| 3 | Redis 지움: 알림 발행기, 안전망, 레이드 메모리 저장소, 종료 때 쓰기, 맞추기, 테스트 지원 코드, CI와 e2e의 Redis | US3 | 테스트, k6 500명 정확성 |
| 4 | JDK 25, Dockerfile(AOT 캐시, 플래그), 톰캣 스레드 | FR-010 | Q1 |
| 5 | `render.yaml`, `release.yml`(GHCR + 배포 훅), `ops-daily.yml`, R2 | US2, US6 | Q7 |
| 6 | 리허설: Neon 복원, Render 운영 설정, 측정 | US1, US2 | Q2~Q6, 웹 리전 결정 |
| 7 | 전환(quickstart), 7일 관찰 | US1 | quickstart 전환 표 |
| 8 | 정리: VM, 디스크, IP, 정리 정책, ADR-0007, 문서 | US7 | 다음 달 청구서 |

묶음 6~8은 계정이 있어야 한다(quickstart 0). 묶음 8의 삭제는 저장소 소유자가 승인한다.

## Complexity Tracking

위반이 없다.
