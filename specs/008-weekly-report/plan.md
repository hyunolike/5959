# Implementation Plan: 주간 리포트

**Branch**: `008-weekly-report` | **Date**: 2026-10-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/008-weekly-report/spec.md`

## Summary

매주 월요일에 지난주에 글을 쓴 회원마다 리포트를 만든다. 리포트는 지난주의 감정별 글 수, 처치된 몬스터 수, 받은 공감과 댓글 수를 담고, AI가 그 수치로 쓴 짧은 편지가 붙는다. 알림으로 알리고 리포트 화면에서 본다. 마이페이지에서 지난 리포트를 다시 본다.

API에는 `report` 모듈을 추가한다. 한 번 도는 배치가 아니라 1분마다 도는 주기 작업이 "지난주 대상 회원 가운데 리포트가 없는 회원"을 채운다. 그래서 일부가 실패해도 나머지는 나가고, 실패한 회원과 서버가 내려가 있던 동안의 몫은 다음 차례에 이어진다. 겹치지 않게 하는 것은 `(회원, 주)` 유일 제약이다. 편지는 리포트와 따로, 감정 분석과 같은 재시도 일정으로 쓴다. 공급자에는 수치만 든 값을 보내고, 위기로 판정된 글이 있던 주에는 AI에게 맡기지 않는다.

웹은 리포트 화면과 마이페이지의 목록, 알림 종류 하나를 더한다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**:
- API: Spring Boot 4.1, Spring Modulith 2.1, Spring AI(채팅 클라이언트, 감정 분석과 같다), Resilience4j(`weeklyLetter` 인스턴스 추가). 새 라이브러리는 없다. ShedLock은 넣지 않는다(R4)
- 웹: Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. Flyway `V8__weekly_report.sql`로 `weekly_report`, `weekly_report_run`을 만들고 `notification`에 종류와 열 하나, `post`에 색인 둘을 더한다

**Testing**:
- API: JUnit 5, MockMvc, Testcontainers. 시계를 주입하고 글과 공감, 댓글의 때를 직접 넣어 집계를 확인한다. 일부 실패는 테스트용 빈으로 만든다. 가짜 편지 쓰기는 수치로 결과가 정해진다. 실제 모델의 편지는 켰을 때만 도는 테스트가 출력한다
- 웹: Vitest, Playwright(`e2e-full/weekly-report.spec.ts`)

**Target Platform**: Oracle Cloud ARM VM의 Docker(API), Vercel `icn1`(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**:
- 대상 회원 1천 명의 리포트 만들기에 걸린 시간을 잰다. 그동안 글 쓰기 p95가 늘지 않는다(SC-004)
- 리포트 화면과 목록 조회 p95 300ms 이하(SC-005)

**Constraints**:
- AI가 실패해도 리포트와 알림은 제때 나간다(constitution V, SC-003)
- 위기 글이 있던 주에는 AI 편지를 쓰지 않는다(constitution IV, FR-011)
- 공급자에 수치만 보낸다. 수치와 편지를 로그와 이벤트에 남기지 않는다(FR-010, FR-015)
- `posts`, `monsters`, `emotion_analysis`를 `report`에서 직접 읽지 않는다
- 월 인프라 비용 증가 0원. AI 호출은 리포트마다 한 번이다

**Scale/Scope**: 인수 조건 25개(US1 9, US2 7, US3 5, US4 4). 새 모듈 1개, 새 테이블 2개, 계약 연산 2개와 알림 종류 1개, 파사드 추가(`post` 3, `monster` 1), 웹은 엔티티 1개, 위젯 1개, 화면 1개, 마이페이지 탭 1개

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈 `report`는 `shared`, `post`, `monster`, `emotion`, `ai`, `member`에 의존하고 `notification → report`가 더해진다. overview 5.1의 표에 있던 모듈이다. 다른 모듈의 데이터는 파사드로만 읽는다(R1, R5). `ModularityTests`가 확인한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/weekly-report.openapi.yaml](contracts/weekly-report.openapi.yaml)에 연산 2개와 알림 종류를 먼저 적었다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 25개에 ID를 붙였다. 편지의 품질은 수치로 재지 않고 규칙만 검증한다(스펙 Assumptions) | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 위기 글이 있던 주에는 AI에게 맡기지 않고 정해 둔 문구와 도움받을 곳을 보인다(R8). 편지는 길이, 숫자, 욕설을 검증한 뒤에만 보인다(R7). 리포트는 본문을 담지 않는다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | 리포트와 알림은 AI를 부르기 전에 나간다. 편지는 일정 테이블로 재시도하고 24시간 뒤 닫는다(R6) | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너가 없다. 공급자의 사용료는 없다. 대상 회원 수만큼 AI를 부르므로 한 차례의 한도로 몰림을 묶는다(R3) | 통과 |

**Phase 1 설계 후 재확인**:
- `weekly_report`, `weekly_report_run`은 `report`가 소유한다. 다른 모듈의 테이블에 FK를 걸지 않고 조인하지 않는다.
- `report`는 `WeeklyReportPublished` 하나를 낸다. 수치와 편지를 싣지 않는다.
- overview 5.7이 적은 ShedLock을 쓰지 않는다. 잠금이 아니라 유일 제약이 "한 번만"을 지킨다(R4). overview를 고친다.
- 위반은 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/008-weekly-report/
├── spec.md, plan.md, research.md(R1~R11), data-model.md, quickstart.md
├── contracts/weekly-report.openapi.yaml
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
apps/api/src/main/
├── java/com/ogu/report/package-info.java          # allowedDependencies: shared, post, monster, emotion, ai
├── kotlin/com/ogu/
│   ├── report/
│   │   ├── WeeklyReportPublished.kt
│   │   ├── application/
│   │   │   ├── ReportProperties.kt, WeekRange.kt
│   │   │   ├── WeeklyStatsCollector.kt            # 파사드로 읽어 회원 한 명의 한 주를 센다
│   │   │   ├── WeeklyReportPublisher.kt           # 넣기와 이벤트(한 트랜잭션), 넣은 쪽만
│   │   │   ├── WeeklyReportJob.kt                 # 지난주 대상 가운데 없는 회원 채우기, 끝났다고 적기
│   │   │   ├── WeeklyLetterPipeline.kt            # 맡기, 쓰기, 결과 적기, 재시도
│   │   │   ├── WeeklyReportQueryService.kt
│   │   │   └── WeeklyReportSchedulers.kt, WeeklyReportPurgeJob.kt
│   │   ├── domain/WeeklyReportRepository.kt, WeeklyReportRunRepository.kt
│   │   └── presentation/WeeklyReportController.kt, dto/
│   ├── ai/
│   │   ├── WeeklyLetterWriter.kt                  # WeeklyLetterInput(수치만), WeeklyLetterFailed
│   │   └── infrastructure/SpringAiWeeklyLetterWriter.kt, FakeWeeklyLetterWriter.kt, WeeklyLetterValidator.kt
│   ├── post/PostReportApi.kt                      # 기간 안에 글 쓴 회원, 회원의 기간 안 글, 받은 공감과 댓글 수
│   ├── monster/MonsterApi.kt                      # defeatedCountBetween
│   └── notification/                              # WEEKLY_REPORT, report_week_start
└── resources/
    ├── db/migration/V8__weekly_report.sql
    └── prompts/weekly-letter.st

apps/web/src/
├── entities/weekly-report/                        # 타입, 조회, 폴링 간격
├── widgets/weekly-report/                         # 리포트 본문(편지, 감정 막대, 수치, 앞 주와 견주기)
├── widgets/my-activity/                           # "주간 리포트" 탭
├── entities/notification/                         # WEEKLY_REPORT 문구와 이동
└── app/report/[weekStart]/page.tsx

apps/web/e2e-full/weekly-report.spec.ts
apps/api/src/test/resources/seed/m7-weekly-report-perf.sql
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 모듈 하나를 더하고, 웹은 FSD 슬라이스 둘과 화면 하나를 더한다.

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, V8 마이그레이션, `report` 모듈 뼈대, 설정 | 기반 |
| 2 | 파사드 추가(`post`, `monster`), 집계(`WeeklyStatsCollector`) | US1 |
| 3 | 발행(넣기와 이벤트), 알림 종류, 조회 API | US1 |
| 4 | 리포트 만들기 주기 작업(채우기, 일부 실패, 이어 하기, 겹치지 않음) | US3 |
| 5 | 편지: `WeeklyLetterWriter`와 실제, 가짜 구현, 검증, 재시도 일정, 위기 주 | US2 |
| 6 | 목록 조회, 앞 주와 견주기, 보관 정리 | US4 |
| 7 | 웹: 리포트 화면, 알림, 마이페이지 탭, e2e | US1, US2, US4 |
| 8 | 1천 명 측정, 실제 모델의 편지 확인 | 마무리 |
| 9 | 인수 조건 점검, 문서, quickstart 실행, PR | 마무리 |

## Complexity Tracking

위반이 없다.
