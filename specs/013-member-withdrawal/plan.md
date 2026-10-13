# Implementation Plan: 회원 탈퇴

**Branch**: `013-member-withdrawal` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/013-member-withdrawal/spec.md`

## Summary

회원이 스스로 계정을 지운다. 탈퇴하면 바로 로그아웃되고 다시 로그인할 수 없으며, 그 회원의 글과 댓글은 지운 것과 똑같이 어디에도 보이지 않는다. 다른 회원에게 준 공감 수와 줄인 HP, 레이드 기여는 숫자로 남는다. 같은 이메일, 소셜 계정, 닉네임은 바로 다시 쓸 수 있다.

회원 행은 지우지 않고 개인 정보를 비운다. `member`가 `MemberWithdrawn` 이벤트 하나를 내면, `post`가 같은 트랜잭션에서 글과 댓글을 지운 것으로 바꾸고, 커밋 뒤에 `post`, `notification`, `report`가 본문과 알림, 주간 리포트를 지운다. 새 상태를 만들지 않고 지금의 `deleted_at`과 `PostRemoved`, `CommentRemoved`를 그대로 써서, 피드와 추천, 도감, 신고 닫기가 고치지 않아도 따라온다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1(Event Publication Registry) / Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. 마이그레이션 `V13__member_withdrawal.sql`(`member.withdrawn_at`, 제약 둘, `comments` 색인 하나). 새 테이블은 없다. Redis는 실시간 연결을 닫는 신호에만 쓴다

**Testing**: JUnit 5, MockMvc, Testcontainers, Modulith `Scenario` / Vitest, Playwright

**Target Platform**: VM의 docker compose(API, 손으로 배포), Cloud Run(웹, 병합하면 배포)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 탈퇴 요청은 글과 댓글의 수와 상관없이 문장 수가 고정이다. 글 1,000개와 댓글 5,000개에서 3초 안에 응답하고 정리는 10분 안에 끝난다(SC-006)

**Constraints**:
- 탈퇴와 "글과 댓글이 보이지 않는다"가 한 트랜잭션이다(FR-007)
- 다른 회원의 공감 수, HP, 레이드의 숫자를 바꾸지 않는다(FR-009)
- 정리는 다시 돌아도 결과가 같다. 댓글 수가 두 번 줄지 않는다(FR-017)
- 이벤트, 로그, 발행 기록에 본문과 개인 정보를 싣지 않는다
- 모듈의 의존 방향을 바꾸지 않는다. `member`는 다른 모듈을 부르지 않는다

**Scale/Scope**: 인수 조건 41개(US1 12, US2 10, US3 8, US4 5, US5 6). 계약 연산 3개와 스키마 3개. API는 `member`(탈퇴, 이벤트, 파사드 2), `post`(동기 지우기, 비우기, 저장 뒤 확인), `notification`(받는 쪽 거르기, 지우기, 닫는 신호), `report`(지우기), `safety`(조회 하나). 웹은 화면 하나와 BFF 라우트 하나, 들어가는 길 둘

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 의존이 없다. `post`, `notification`, `report`가 `member`의 이벤트를 받는 것은 지금의 허용 표 안이다. `member`는 여전히 `shared`에만 의존한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/member-withdrawal.openapi.yaml](contracts/member-withdrawal.openapi.yaml)을 루트 계약에 먼저 합친다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 41개에 ID가 있다. 화면의 접근성(US1-AC2, AC3)은 Vitest와 Playwright로, 백업 되살리기는 문서로 다룬다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 최근에 위기 판정이 있던 회원의 탈퇴 화면에 도움받을 곳을 보인다(FR-019). 위험 판정과 신고 기록은 보관 기간 동안 남긴다. 탈퇴를 말리는 어두운 수법을 쓰지 않는다(FR-020) | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 부르지 않는다. 지운 글은 감정 분석과 임베딩, 위험 분류의 재시도에서 본문 없이 닫힌다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 자원이 없다 | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

## Project Structure

```text
apps/api/src/main/resources/db/migration/V13__member_withdrawal.sql

apps/api/src/main/kotlin/com/ogu/
├── member/
│   ├── MemberWithdrawn.kt, MemberInfo.kt(withdrawn), MemberApi.kt(isWithdrawn)
│   ├── domain/Member.kt(withdraw), 세션과 소셜 연결, 연결 표의 리포지토리
│   ├── application/WithdrawalService.kt, WithdrawalCleanupQuery.kt, MemberQueryService.kt
│   ├── presentation/MemberController.kt(withdraw), WithdrawalOperatorController.kt, dto/
│   └── infrastructure/security/SecurityPaths.kt                      # 온보딩 전 허용
├── post/
│   ├── application/WithdrawnContentRemover.kt                        # 동기: 지운 것으로 바꾸기
│   ├── application/WithdrawnContentCleaner.kt                        # 커밋 뒤: 이벤트 내고 본문 비우기
│   ├── application/WithdrawalListener.kt
│   └── application/PostService.kt, CommentService.kt                 # 저장 뒤 isWithdrawn 확인
├── notification/
│   ├── application/NotificationWriter.kt                             # 탈퇴한 회원에게 쓰지 않는다
│   ├── application/WithdrawalListener.kt, domain/ 리포지토리
│   └── stream/NotificationSignal.kt(Closed), SseHub.kt(closeMember)
├── report/application/WithdrawalListener.kt, domain/ 리포지토리
├── safety/presentation/SafetyController.kt, application/              # withdrawal-notice
└── shared/lock/PostLock.kt(lockAll), shared/error/ErrorCode.kt

apps/api/src/test/kotlin/com/ogu/
├── member/…/WithdrawalApiTests.kt, WithdrawalRejoinTests.kt
├── post/…/WithdrawnContentTests.kt, WithdrawalCleanupTests.kt, WithdrawalRaceTests.kt
├── notification/…/WithdrawalNotificationTests.kt, report/…/WithdrawalReportTests.kt
├── feed/…/WithdrawalVisibilityTests.kt                                # 화면에 닿는 조회마다 탈퇴한 회원의 글이 없는지
└── WithdrawalMigrationTests.kt

apps/web/src/
├── app/withdraw/page.tsx
├── app/api/auth/withdraw/route.ts(+ test)
├── features/withdraw/                 # useWithdrawMutation, WithdrawForm
├── entities/member/                   # useWithdrawalNoticeQuery
├── shared/server/route-guard.ts       # /withdraw는 로그인만 본다
├── app/my/edit/page.tsx, app/onboarding/page.tsx                      # 들어가는 길
└── app/(auth)/login/page.tsx                                          # 탈퇴했다는 안내
apps/web/e2e-full/withdrawal.spec.ts
contracts/openapi.yaml                 # 0.14.0
infra/RESTORE.md, apps/api/AGENTS.md, docs/architecture/overview.md, docs/features.md, README.md
```

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, 웹 타입 다시 만들기, 마이그레이션 | 기반 |
| 2 | `member`: 회원 비우기, 세션과 연결 지우기, 이벤트, 탈퇴 API, 운영자와 확인 거절 | US1, US4 |
| 3 | `post`: 동기 지우기, 저장 뒤 확인, 커밋 뒤 비우기 | US2, US3, US5 |
| 4 | `notification`, `report`: 받는 쪽 거르기, 지우기, 연결 닫기 | US2, US3 |
| 5 | `safety`의 안내 조회, 운영자의 정리 상태 조회 | US1-AC10, US5-AC6 |
| 6 | 웹: BFF 라우트, 화면, 들어가는 길, e2e | US1 |
| 7 | 문서, 검토, PR | 마무리 |

## Complexity Tracking

위반이 없다.
