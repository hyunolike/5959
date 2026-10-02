# Quickstart: 핵심 루프 검증 (003-core-loop)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/core-loop.openapi.yaml](contracts/core-loop.openapi.yaml)(구현 후에는 저장소 루트 `contracts/openapi.yaml`), 데이터는 [data-model.md](data-model.md)를 본다.

## 준비

```bash
# API: e2e 프로필은 가짜 감정 분석기를 쓴다(research R3)
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e ./gradlew bootRun

# 웹
API_ORIGIN=http://localhost:8080 BFF_API_KEY=local-bff-key APP_ORIGIN=http://localhost:3000 \
  pnpm --filter web dev
```

가짜 분석기 규칙: 본문이 `[불안:높음]`처럼 시작하면 그 감정과 강도를, `[실패]`로 시작하면 항상 예외를, `[실패:N]`으로 시작하면 처음 N번만 예외를 던지고 그다음에는 본문 길이로 정한 값을 돌려준다. 그 밖에는 본문 길이로 정한 값을 돌려준다.

## 자동 검증

```bash
cd apps/api && ./gradlew test        # 모듈, 통합, 계약, 동시성 테스트
pnpm --filter web test               # 외형 함수, 낙관적 HP, 글자 수, 폴링
pnpm --filter web test:e2e:full      # 전체 흐름 (CI의 e2e-full)
grep -rn "US3-AC9" apps/             # 인수 조건에서 테스트 찾기
```

## 수동 검증 시나리오 (로컬)

회원 A, B, C 세 명을 가입과 온보딩까지 마친 상태에서 시작한다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | A가 `[불안:낮음] 내일 발표가 걱정돼요`를 "무조건 위로해주기"로 작성 | 상세로 이동, 몬스터 자리에 "분석 중" | US1-AC1, US1-AC3 |
| 2 | 몇 초 기다림 | 새로고침 없이 불안 몬스터(HP 10/10) 등장 | US1-AC3, US1-AC4 |
| 3 | A가 자기 글에 공감 시도, 댓글 작성 | 공감 버튼 없음, 댓글은 달리지만 HP 10 그대로 | US3-AC8 |
| 4 | B가 공감, 댓글 2개 | HP 10 → 9 → 6, 두 번째 댓글 뒤에도 6 | US3-AC1, US3-AC2 |
| 5 | C가 B의 댓글에 공감, 댓글 | HP 6 → 5 → 2 | US3-AC4, US3-AC2 |
| 6 | C가 답글 작성 | 답글이 원 댓글 아래에 보이고 HP 2 그대로(C의 두 번째 댓글) | US3-AC3 |
| 7 | B가 공감 취소 후 다시 공감 | 공감 수는 바뀌지만 HP 2 그대로 | US3-AC6 |
| 8 | 회원 D가 공감, 댓글 | HP 2 → 1 → 0, 쓰러진 몬스터 | US3-AC5 |
| 9 | `[실패:2] 오늘은 아무것도 하기 싫다` 작성 직후 B, C가 공감 | 분석 중 유지, 공감 수 2 | US1-AC5, US3-AC9 |
| 10 | 약 1분 30초 기다림(30초, 60초 뒤 재시도) | 세 번째 시도에서 몬스터가 생기고 HP가 공감 2만큼 줄어 있음 | US1-AC5, US3-AC9 |
| 11 | 피드에서 인기순, 직군 필터 | 공감 많은 글부터, 고른 직군의 글만 | US2-AC3, US2-AC4 |
| 12 | 개발자 도구에서 "움직임 줄이기" 에뮬레이션 | 상세의 몬스터가 정지 이미지로 바뀜 | US5-AC4 |

## 동시성 확인

```bash
cd apps/api && ./gradlew test --tests "*MonsterConcurrencyTest*" --rerun-tasks
```

100명 동시 공감 뒤 HP와 기록 수가 정확한지 본다(SC-004).

## 성능 측정

T(마무리 단계)에서 글 1만 개를 넣고 피드 첫 페이지와 다음 페이지 100회의 p95를 재서 이 문서에 표로 남긴다(SC-003). 측정 환경의 부하 상태도 함께 적는다.

## 운영 준비 (저장소 소유자)

1. 감정 분석 공급자 키를 발급한다. 기본값은 NVIDIA NIM 무료 엔드포인트다(build.nvidia.com에서 API 키 발급).
2. VM `/opt/ogu/.env`에 `AI_BASE_URL`, `AI_API_KEY`, `AI_MODEL`을 넣는다(값이 비면 prod 기동이 실패하도록 구현한다).
