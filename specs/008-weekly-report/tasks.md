---

description: "Task list for 008-weekly-report (주간 리포트)"
---

# Tasks: 주간 리포트

**Input**: Design documents from `/specs/008-weekly-report/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 25개다(US1 9, US2 7, US3 5, US4 4).

**Organization**: plan의 구현 순서(묶음 1~9)를 따른다.

## Phase 1: Setup (묶음 1)

- [ ] T001 `contracts/weekly-report.openapi.yaml`을 루트 계약에 합친다(0.8.0, `getMyWeeklyReports`, `getMyWeeklyReport`, `WeeklyReport`, `WeeklyLetterStatus`, 알림 종류 `WEEKLY_REPORT`와 `reportWeekStart`). `pnpm --filter web gen:api`
- [ ] T002 `V8__weekly_report.sql`: `weekly_report`, `weekly_report_run`, 제약과 색인, `notification`의 종류와 `report_week_start`와 대상 CHECK, `post`의 색인 둘. `WeeklyReportMigrationTests`
- [ ] T003 `report` 모듈 뼈대(`package-info.java`, `ReportProperties`, `WeekRange`, `application.yml`의 `ogu.report.*`, `ogu.ai.letter-max-tokens`, resilience4j `weeklyLetter`). `ModularityTests`에 `report`와 `notification → report`를 더한다. `WeekRangeTest`(월요일 0시 경계, 일요일 23:59:59, 해가 바뀌는 주)

## Phase 2: 집계 US1 (묶음 2)

- [ ] T004 [P] `post`: `PostReportApi`(기간 안에 지우지 않은 글을 쓴 회원 번호를 순서대로, 회원의 기간 안 글의 번호와 쓴 때와 위험 단계, 회원의 글에 기간 안에 달린 다른 회원의 공감 수와 댓글 수)와 구현, 테스트
- [ ] T005 [P] `monster`: `MonsterApi.defeatedCountBetween(postIds, from, to)`와 테스트
- [ ] T006 `WeeklyStatsCollectorTests`: `US1-AC3 글 수, 감정별 수, 가장 많은 감정, 처치 수, 받은 공감과 댓글 수`, `US1-AC5 한국 시간 월요일 0시가 경계다`, `US1-AC6 지운 글은 빼고 숨겨진 내 글은 센다`, `US1-AC7 분석되지 않은 글은 글 수에만 든다`, 내가 내 글에 단 댓글과 지운 댓글은 세지 않는다, 가장 많은 감정이 같으면 최근 글의 감정, 위기 글 여부(SC-008)
- [ ] T007 `WeeklyStatsCollector`

## Phase 3: 발행과 조회 US1 (묶음 3)

- [ ] T008 [P] `WeeklyReportRepository`(넣기 `ON CONFLICT DO NOTHING`, 한 주 찾기, 회원의 목록, 한 주에 리포트가 있는 회원 번호), `WeeklyReportRunRepository`
- [ ] T009 `WeeklyReportPublisher`(집계, 넣기, 넣은 쪽만 `WeeklyReportPublished`를 같은 트랜잭션에서 낸다). 글이 없으면 만들지 않는다
- [ ] T010 `notification`: `WEEKLY_REPORT` 종류, `NotificationDraft.weeklyReport`, 리스너, 응답의 `reportWeekStart`. 테스트: `US1-AC1 리포트가 생기면 알림이 하나 온다`, 이벤트가 다시 와도 알림은 하나
- [ ] T011 `WeeklyReportApiTests`: `US1-AC3 리포트 조회`, `US1-AC4 글을 쓰지 않은 회원은 리포트도 알림도 없다`, `US1-AC8 발행 뒤 글을 지워도 수치는 그대로다`, `US1-AC9 로그인하지 않으면 401, 내 것이 아니거나 없는 주는 404`, 월요일이 아닌 날짜와 틀린 형식은 404
- [ ] T012 `WeeklyReportQueryService`, `WeeklyReportController`(한 주 조회)

## Phase 4: 리포트 만들기 US3 (묶음 4)

- [ ] T013 `WeeklyReportJobTests`(시계 주입, 스케줄러 끔): `US3-AC1 일부 회원이 실패해도 나머지는 발행된다`, `US3-AC2 실패한 회원은 다음 실행에 발행되고 받은 회원은 다시 받지 않는다`, `US3-AC3 여러 번, 함께 돌려도 리포트와 알림은 하나다`, `US3-AC4 중간에 멈췄다 다시 돌면 남은 회원부터 한다`, `US3-AC5 그 주 안에 늦게 돌아도 만들고 한 주가 넘으면 만들지 않는다`, 발행 시각 전에는 만들지 않는다, 한 바퀴를 깨끗이 돌면 끝났다고 적고 다시 훑지 않는다, 한 차례의 한도를 넘지 않는다
- [ ] T014 `WeeklyReportJob`, `WeeklyReportScheduler`(`ogu.report.scheduler-enabled`). 테스트용 실패 주입 빈(`ReportFaults`)

## Phase 5: 편지 US2 (묶음 5)

- [ ] T015 [P] `ai`: `WeeklyLetterWriter`, `WeeklyLetterInput`(수치만), `WeeklyLetterFailed`, `WeeklyLetterValidator`(빈 답, 300자, 보낸 수치에 없는 숫자), `prompts/weekly-letter.st`, `SpringAiWeeklyLetterWriter`(서킷 브레이커, 답을 로그에 남기지 않는다), `FakeWeeklyLetterWriter`, 설정 빈
- [ ] T016 [P] `WeeklyLetterValidatorTest`(`US2-AC7 비어 있거나 긴 답은 실패다`, 없는 숫자), `SpringAiWeeklyLetterWriterTest`(가짜 HTTP 서버: `US2-AC5 요청에 수치만 있다`, 타임아웃, 서킷 열림), `WeeklyLetterLiveTest`(켰을 때만: 수치 묶음 다섯 개의 편지를 출력한다)
- [ ] T017 `WeeklyLetterPipelineTests`: `US2-AC1 편지가 리포트에 보인다`, `US2-AC2 AI가 실패해도 리포트와 알림은 나가고 편지는 PENDING이다`, `US2-AC3 다시 시도해 써지면 DONE이 되고 알림은 다시 가지 않는다`, `US2-AC4 24시간 뒤에는 GIVEN_UP이다`, `US2-AC6 위기 글이 있던 주는 SUPPORT이고 AI를 부르지 않는다`, 가려지는 낱말이 든 답은 실패다, 로그와 이벤트에 수치와 편지가 없다(SC-007)
- [ ] T018 `WeeklyLetterPipeline`(발행 커밋 뒤 한 번 시도, 맡기, 결과 적기), `WeeklyLetterRetryScheduler`

## Phase 6: 목록과 보관 US4 (묶음 6)

- [ ] T019 테스트와 구현: `US4-AC1 최신 주부터 목록`, `US4-AC3 없으면 빈 목록`, `US4-AC4 앞 주의 리포트가 있으면 견준 값이 있고 없으면 null`, 커서. `WeeklyReportPurgeJob`(1년)

## Phase 7: 웹 (묶음 7)

- [ ] T020 [P] `entities/weekly-report`(타입, `useWeeklyReportQuery`: `PENDING`이면 3초마다 최대 30초, `useWeeklyReportsQuery`), 단위 테스트
- [ ] T021 `widgets/weekly-report`(기간, 편지 구역의 네 상태, 감정 막대, 수치, 앞 주와 견주기), `app/report/[weekStart]/page.tsx`, 라우트 가드. 단위 테스트: `US1-AC3`, `US1-AC7`, `US2-AC1`, `US2-AC2`, `US2-AC3`, `US2-AC4`, `US2-AC6`, `US4-AC4`, 없는 리포트 안내
- [ ] T022 알림 `WEEKLY_REPORT`의 문구와 이동(`US1-AC2`), 마이페이지 "주간 리포트" 탭(`US4-AC1`, `US4-AC2`, `US4-AC3`). 단위 테스트
- [ ] T023 `e2e-full/weekly-report.spec.ts`: `US1-AC1`과 `US1-AC2`와 `US1-AC3`, `US1-AC4`, `US1-AC9`, `US2-AC3`, `US2-AC6`, `US4-AC1`과 `US4-AC2`. e2e 프로필의 `ogu.report.*`

## Phase 8: 측정 (묶음 8)

- [ ] T024 `seed/m7-weekly-report-perf.sql`(회원 1천 명과 지난주 글)과 측정(SC-001, SC-004, SC-005). 결과를 quickstart.md에 남긴다
- [ ] T025 실제 모델의 편지 다섯 개를 받아 읽고 프롬프트를 다듬는다. 결과를 research R7에 남긴다

## Phase 9: 마무리 (묶음 9)

- [ ] T026 [P] 인수 조건 25개가 모두 테스트 이름에 있는지 확인한다
- [ ] T027 [P] 문서: apps/api/AGENTS.md, apps/web/docs/ARCHITECTURE.md, overview 5.1과 5.7(ShedLock을 쓰지 않는 까닭), README
- [ ] T028 quickstart의 수동 시나리오 17개를 로컬에서 실행한다
- [ ] T029 PR을 연다
