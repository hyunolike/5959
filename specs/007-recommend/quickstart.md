# Quickstart: 비슷한 고민 추천 검증 (007-recommend)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/recommend.openapi.yaml](contracts/recommend.openapi.yaml), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다.

## 준비

```bash
# e2e 프로필은 가짜 임베더를 쓴다. 본문의 표지로 방향을 정한다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e ./gradlew bootRun
pnpm --filter web dev
```

가짜 임베더의 표지(본문 어디든):

| 표지 | 결과 |
|---|---|
| `[주제:이름]` | 같은 이름끼리 가깝고 다른 이름과는 멀다 |
| (없음) | 어떤 글과도 가깝지 않다 |
| `[임베딩실패]` | 계속 실패 |
| `[임베딩실패:2]` | 두 번 실패한 뒤 성공 |

## 자동 검증

```bash
cd apps/api && ./gradlew test
pnpm --filter web test
pnpm --filter web test:e2e:full          # recommend.spec.ts
# 실제 공급자를 부르는 확인과 평가(키가 있을 때만)
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*EmbedderLiveTest*" --tests "*RecommendEvalTest*" -i | grep -E "LIVE|EVAL"
```

## 수동 검증 시나리오 (로컬)

회원 A, B, C가 가입과 온보딩을 마친 상태에서 시작한다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | B, C가 `[주제:야근]` 글을 셋, `[주제:이직]` 글을 둘 쓰고 A가 `[주제:야근]` 글을 써서 상세를 엶 | 30초 안에 "비슷한 고민"에 야근 글 셋이 가까운 순서로. 이직 글은 없음 | US1-AC1, US1-AC4, US1-AC7 |
| 2 | 1번의 추천 하나를 누름 | 그 글의 상세로 감 | US1-AC2 |
| 3 | A가 `[주제:야근]` 글을 하나 더 쓰고 첫 글을 엶 | A의 다른 글과 지금 글은 추천에 없음 | US1-AC3 |
| 4 | B가 욕설이 든 `[주제:야근]` 글을 쓰고 A가 추천을 봄 | 욕설이 가려져 보임. 카드에 몬스터, 공감 수, 댓글 수 | US1-AC5, US1-AC6 |
| 5 | A가 `[임베딩실패] [불안:낮음]` 글을 씀 | 글은 바로 저장됨. 상세의 구역 제목이 "같은 감정의 고민"이고 불안 글이 보임 | US2-AC1, US2-AC2 |
| 6 | A가 `[임베딩실패] [실패]` 글을 씀(감정 분석도 실패) | 추천 구역이 없음 | US2-AC3 |
| 7 | A가 `[임베딩실패:2] [주제:야근]` 글을 쓰고 1분 30초 기다림 | 처음에는 같은 감정의 글 또는 없음, 그 뒤 "비슷한 고민"으로 바뀜 | US2-AC4 |
| 8 | 1번의 추천에 나오던 B의 글을 B가 지움 | A의 추천에서 사라짐 | US3-AC1 |
| 9 | 추천에 나오던 C의 글을 위기 표현으로 고침(숨겨짐) | A의 추천에서 사라짐. C가 자기 글을 열면 추천이 보임 | US3-AC2, US3-AC5 |
| 10 | B가 `[주제:야근]` 글을 `[주제:이직]`으로 고침 | 잠시 뒤 그 글의 추천이 이직 글로 바뀜. 그 사이 구역은 비지 않음 | US3-AC3, US3-AC4 |
| 11 | `post_embedding`을 비우고 API를 다시 띄움 | 이미 있는 글이 차례로 처리됨. 그동안 새로 쓴 글의 추천은 30초 안에 | US4-AC1, US4-AC2 |
| 12 | 11번 도중 API를 죽였다 다시 띄움 | 남은 글부터 이어서 처리. 끝난 글의 시도 횟수는 그대로 | US4-AC3 |
| 13 | 브라우저에서 추천 조회만 실패하게 막고 글 상세를 엶 | 글과 댓글은 보이고 추천 구역만 없음 | US2-AC7 |

## 평가 (SC-001, SC-002)

```bash
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*RecommendEvalTest*" -i | grep EVAL
```

기준값(`ogu.recommend.max-distance`)을 바꿔 가며 "추천 5개 안에 같은 주제가 하나 이상인 비율"과 "기준 안에 든 다른 주제의 비율"을 출력한다. 표를 보고 기준값을 정한 뒤, 따로 써 둔 묶음으로 한 번 더 잰다.

## 성능 측정 (SC-003, SC-004, SC-005)

```bash
docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 < apps/api/src/test/resources/seed/m6-recommend-perf.sql
```

- SC-003: 글 1만 건(무작위 값)에서 추천 조회를 100번 불러 p50, p95를 남긴다.
- SC-004: 실제 공급자로 글을 쓰고 추천이 `SIMILAR`가 되기까지를 10번 잰다.
- SC-005: 글 쓰기 100번의 p95를 M5까지의 수치와 견준다.

## 운영 준비 (저장소 소유자)

1. 운영 DB에 pgvector 확장이 있는지 확인한다. 이미지가 `pgvector/pgvector:pg17`이면 V7이 만든다.
2. 배포 뒤 이미 있는 글이 처리되는지 본다(`select status, count(*) from post_embedding group by status`).
3. `embedder` 서킷 브레이커가 닫혀 있는지 본다. 임베딩 모델도 공급자가 내릴 수 있다. 그때는 `AI_EMBEDDING_MODEL`을 바꾸면 값을 차례로 다시 만든다.
