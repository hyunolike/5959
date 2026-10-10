# Quickstart: 주간 리포트 검증 (008-weekly-report)

구현이 끝난 뒤 기능이 처음부터 끝까지 동작하는지 확인하는 절차다. 계약은 [contracts/weekly-report.openapi.yaml](contracts/weekly-report.openapi.yaml), 데이터는 [data-model.md](data-model.md), 결정 근거는 [research.md](research.md)를 본다.

## 준비

```bash
# e2e 프로필은 가짜 편지 쓰기를 쓰고, 발행 시각 제한 없이 5초마다 리포트를 만든다
cd apps/api && SPRING_PROFILES_ACTIVE=local,e2e ./gradlew bootRun
pnpm --filter web dev
```

지난주의 글은 화면으로 만들 수 없다. 글을 쓴 뒤 때를 한 주 앞으로 옮긴다.

```sql
update posts set created_at = created_at - interval '7 days' where id in (...);
```

가짜 편지 쓰기는 본문을 받지 않으므로 수치로 결과가 정해진다.

| 수치 | 결과 |
|---|---|
| 받은 댓글 수가 13 | 계속 실패 |
| 쓴 글 수가 7 | 두 번 실패한 뒤 성공 |
| 그 밖 | 수치로 정해지는 편지 |

## 자동 검증

```bash
cd apps/api && ./gradlew test
pnpm --filter web test
pnpm --filter web test:e2e:full          # weekly-report.spec.ts
# 실제 모델이 쓴 편지를 출력한다(키가 있을 때만). 사람이 읽는다
OGU_RUN_AI_EVAL=true AI_API_KEY=... ./gradlew test --tests "*WeeklyLetterLiveTest*" -i | grep LIVE
```

## 수동 검증 시나리오 (로컬)

회원 A, B, C가 가입과 온보딩을 마친 상태에서 시작한다.

| # | 절차 | 기대 결과 | 인수 조건 |
|---|---|---|---|
| 1 | A가 `[불안:낮음]` 글 둘, `[짜증:낮음]` 글 하나를 쓰고 한 주 앞으로 옮김. B는 글이 없음 | 1분 안에 A에게 리포트 알림 하나. B에게는 없음 | US1-AC1, US1-AC4 |
| 2 | A가 알림을 누름 | 그 주의 리포트 화면. 기간, 글 3, 불안 2와 짜증 1, 가장 많은 감정 불안 | US1-AC2, US1-AC3 |
| 3 | 1번 전에 B가 A의 글에 공감 둘과 댓글 하나를 남기고 그 때도 한 주 앞으로 옮김. A의 몬스터 하나의 `defeated_at`을 지난주로 둠 | 받은 공감 2, 받은 댓글 1, 처치된 몬스터 1 | US1-AC3 |
| 4 | 글의 때를 지난 일요일 23:59:59와 이번 월요일 00:00:00(한국 시간)으로 둠 | 앞의 글만 리포트에 듦 | US1-AC5 |
| 5 | 지난주 글 가운데 하나를 지우고 하나는 위기가 아닌 까닭으로 숨긴 뒤 리포트를 만듦 | 지운 글은 빠지고 숨긴 글은 듦 | US1-AC6 |
| 6 | `[실패]` 글(분석이 끝나지 않음)을 지난주로 옮김 | 글 수에 들고 "분석되지 않은 글 1" | US1-AC7 |
| 7 | 리포트가 나온 뒤 A가 글을 지움 | 리포트의 수치가 그대로 | US1-AC8 |
| 8 | B가 A의 리포트 주소를 엶. 로그인하지 않고 엶 | B는 없는 리포트 안내, 로그인하지 않으면 로그인 화면 | US1-AC9 |
| 9 | 리포트 화면의 편지를 봄 | 수치로 쓴 편지가 보임 | US2-AC1 |
| 10 | C가 받은 댓글이 13이 되게 만들고 리포트를 만듦 | 리포트와 알림은 옴. 편지 자리에 "편지를 쓰고 있어요" | US2-AC2 |
| 11 | C가 글 7개를 쓴 주를 만들고 리포트 화면을 열어 둠 | 1분 30초 안에 새로고침 없이 편지가 나타남. 알림은 하나 그대로 | US2-AC3 |
| 12 | 10번의 리포트의 `published_at`을 25시간 앞으로 옮기고 1분 기다림 | `GIVEN_UP`. 화면에 편지 구역이 없음 | US2-AC4 |
| 13 | 지난주 글 가운데 위기 표현이 든 글이 있는 회원의 리포트 | 편지 대신 정해 둔 문구와 도움받을 곳. AI를 부르지 않음 | US2-AC6 |
| 14 | 리포트 만들기를 다시 돌림(`weekly_report_run`의 행을 지움) | 리포트와 알림이 늘지 않음 | US3-AC3 |
| 15 | 리포트를 만드는 도중 API를 죽였다 다시 띄움 | 남은 회원부터 이어서. 이미 받은 회원은 그대로 | US3-AC4 |
| 16 | 마이페이지의 "주간 리포트"를 엶 | 최신 주부터 목록. 누르면 그 주로 감. 리포트가 없는 회원은 안내 | US4-AC1, US4-AC2, US4-AC3 |
| 17 | 두 주 연속 리포트가 있는 회원이 뒤의 주를 엶 | 글 수의 변화와 가장 많은 감정의 변화가 보임 | US4-AC4 |

`US2-AC5`(공급자에 보내는 내용), `US2-AC7`(답 검증), `US3-AC1`, `US3-AC2`(일부 실패), `US3-AC5`(늦게 뜬 서버)는 자동 테스트가 본다.

## 성능 측정 (SC-001, SC-004, SC-005)

```bash
docker exec -i <postgres 컨테이너> psql -U ogu -d ogu -v ON_ERROR_STOP=1 < apps/api/src/test/resources/seed/m7-weekly-report-perf.sql
```

- SC-001, SC-004: 회원 1천 명과 지난주 글을 넣고 리포트 만들기가 끝날 때까지의 시간, 그동안의 글 쓰기 p95를 잰다. 10%를 실패하게 한 실행도 한 번 한다.
- SC-005: 리포트 화면과 목록 조회를 100번씩 불러 p50, p95를 남긴다.

## 운영 준비 (저장소 소유자)

1. 위기 글이 있던 주에 AI 편지 대신 보이는 문구를 읽어 본다.
2. 실제 모델이 쓴 편지 다섯 개(`WeeklyLetterLiveTest`의 출력)를 읽어 본다.
3. 첫 월요일 뒤 `select letter_status, count(*) from weekly_report group by 1`로 편지가 채워졌는지 본다.
4. 서버의 시간대와 상관없이 한 주는 한국 시간으로 자른다. 확인할 것은 없다.
