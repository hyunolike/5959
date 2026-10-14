---

description: "Task list for 013-member-withdrawal (회원 탈퇴)"
---

# Tasks: 회원 탈퇴

**Input**: Design documents from `/specs/013-member-withdrawal/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 41개다(US1 12, US2 10, US3 8, US4 5, US5 6). 테스트를 먼저 쓰고 실패를 본 뒤 구현한다.

## Phase 1: Setup

- [X] T001 `specs/013-member-withdrawal/contracts/member-withdrawal.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.14.0): `withdraw`(204, 400, 401, 409), `getWithdrawalNotice`, `getWithdrawalCleanupStatus`, 스키마 `WithdrawalRequest`(`required: [confirmed]`), `WithdrawalNotice`(`required: [recentCrisis]`), `WithdrawalCleanupStatus`(`required: [pending, oldestWithdrawnAt]`). `pnpm --filter web gen:api`
- [X] T002 `apps/api/src/main/resources/db/migration/V13__member_withdrawal.sql`: `member.withdrawn_at TIMESTAMPTZ` NULL, `member_email_password_required_for_email_check`를 `withdrawn_at IS NOT NULL OR auth_method <> 'EMAIL' OR (email IS NOT NULL AND password_hash IS NOT NULL)`로 다시 건다, `member_withdrawn_erased_check`(`withdrawn_at IS NULL OR (email IS NULL AND password_hash IS NULL AND nickname IS NULL AND nickname_key IS NULL AND job_role IS NULL AND career_year IS NULL AND onboarded_at IS NULL AND kind = 'HUMAN' AND role = 'MEMBER')`), 색인 `comments_author_id_idx (author_id, id)`. `apps/api/src/test/kotlin/com/ogu/WithdrawalMigrationTests.kt`로 두 제약을 본다. 번호는 올리기 직전에 `origin/main`을 다시 본다

## Phase 2: Foundational

- [X] T003 `shared`: `ErrorCode.OPERATOR_CANNOT_WITHDRAW`(409), `PostLock.lockAll(postIds)`(한 문장, 빈 목록이면 아무것도 하지 않는다)
- [X] T004 `member` 공개 타입: `MemberWithdrawn(memberId, withdrawnAt)`, `MemberInfo.withdrawn`, `MemberApi.isWithdrawn(memberId)`(엔티티가 아니라 값을 읽는 쿼리, research R5). `Member.withdraw(now)`가 개인 정보 열을 비운다

## Phase 3: US1 회원이 스스로 탈퇴한다 (P1)

**Independent Test**: 글과 댓글이 있는 회원으로 탈퇴한 뒤 같은 계정으로 로그인이 되지 않고, 이미 받은 토큰으로 아무것도 할 수 없다.

- [X] T005 [US1] `apps/api/src/test/kotlin/com/ogu/member/presentation/WithdrawalApiTests.kt`를 먼저 쓴다: `US1-AC3 확인이 없으면 400이고 계정은 그대로다`, `US1-AC4 탈퇴하면 204다`, `US1-AC5 탈퇴한 계정으로 로그인하면 없는 계정과 같은 응답이다`, `US1-AC6 탈퇴하면 그 회원의 모든 세션의 토큰이 401이고 refresh도 되지 않는다`, `US1-AC6 연결 표를 받을 수 없다`, `US1-AC9 온보딩 전 회원도 탈퇴한다`, `US1-AC11 운영자는 409이고 계정은 그대로다`, `US1-AC12 토큰이 없으면 401이다`, `US2-AC8 회원 행에 개인 정보가 없고 소셜 연결과 세션이 지워진다`
- [X] T006 [US1] `member/application/WithdrawalService.kt`: 회원 행 `FOR UPDATE`, 운영자와 AI 거절, `Member.withdraw`, `oauth_identity`/`auth_session`/`sse_ticket` 지우기, `MemberWithdrawn` 발행. `presentation/MemberController.kt`에 `POST /api/v1/members/me/withdrawal`, `dto/WithdrawalRequest.kt`. `SecurityPaths.ONBOARDING_ALLOWED`에 더한다
- [X] T007 [P] [US1] `safety`: `GET /api/v1/safety/withdrawal-notice`(최근 30일에 `level = 'CRISIS'`인 내 판정이 있는가). `apps/api/src/test/kotlin/com/ogu/safety/WithdrawalNoticeApiTests.kt`: `US1-AC10 최근에 위기 판정이 있으면 true다`, `US1-AC10 30일이 지났거나 없으면 false다`, 남의 판정은 세지 않는다

## Phase 4: US2 탈퇴한 회원의 글과 기록이 사라진다 (P1)

**Independent Test**: A가 글 둘과 B의 글에 댓글 하나를 쓴 뒤 탈퇴하면 B와 방문자가 어디에서도 A의 글과 댓글을 볼 수 없고 저장된 본문이 비어 있다.

- [X] T008 [US2] `apps/api/src/test/kotlin/com/ogu/feed/presentation/WithdrawnContentApiTests.kt`를 먼저 쓴다: `US2-AC7 탈퇴가 커밋되면 글과 댓글이 지운 것이다`, `US2-AC4 다른 회원의 글의 댓글 수가 지운 만큼 준다`, `US2-AC4 내 원 댓글의 답글도 함께 지워진다`, 지운 글의 댓글 수는 건드리지 않는다, `US2-AC8 정리 뒤 글과 댓글의 본문이 비어 있다`, `US2-AC8 탈퇴 전에 스스로 지운 글의 본문도 비운다`, 함께 지워진 다른 회원의 답글의 본문은 그대로다
- [X] T009 [US2] `post/application/WithdrawnContentRemover.kt`(글, 댓글과 답글, 댓글 수, `PostLock.lockAll`. 문장 수는 글과 댓글의 수와 상관없다)와 `WithdrawnContentListener.kt`의 동기 `@EventListener`
- [X] T010 [US2] `post/application/WithdrawnContentCleaner.kt`: 커밋 뒤 `@ApplicationModuleListener(propagation = NOT_SUPPORTED)`. 먼저 지우기를 한 번 더 돌고, 글과 댓글을 200개씩 `FOR UPDATE SKIP LOCKED`로 맡아 `PostRemoved`, `CommentRemoved`(내 원 댓글의 답글에도)를 내고 `content = ''`
- [X] T011 [US2] `WithdrawnContentApiTests.kt`에 더한다: `US2-AC1 피드와 비슷한 고민 추천에 없다`, `US2-AC2 글 주소는 지운 글의 응답이다`, `US2-AC3 방문자의 피드에 없다`, `US2-AC5 다른 회원의 내 댓글에서 빠진다`, `US2-AC6 다른 회원의 공감한 글에서 빠진다`, `US3-AC6 다른 회원의 도감에서 그 글의 카드가 빠진다`
- [X] T012 [P] [US2] `notification`: `NotificationWriter`가 탈퇴한 회원에게 쓰지 않는다. `application/WithdrawalNotificationListener.kt`가 받은 알림과 번호 행을 지우고(`WithdrawnReceiverRepository`) `NotificationSignal.Closed`를 보낸다. `SseHub`가 그 신호에 연결을 닫는다. `WithdrawnContentApiTests.kt`, `NotificationStreamTests.kt`, `NotificationSignalTest.kt`에 더한다: `US2-AC8 받은 알림이 지워진다`, `FR-011 탈퇴한 회원에게 새 알림을 쓰지 않는다`, `US1-AC6 열린 실시간 연결이 닫힌다`
- [X] T013 [P] [US2] `report`: `application/WithdrawalReportListener.kt`가 주간 리포트를 지운다. `WithdrawnContentApiTests.kt`에 더한다: `US2-AC8 주간 리포트와 편지가 지워진다`, `US2-AC10 탈퇴한 회원의 리포트와 알림은 만들어지지 않는다`
- [X] T014 [US2] `WithdrawnContentApiTests.kt`에 더한다: `US2-AC9 끝나지 않은 감정 분석, 위험 분류, 임베딩, AI 친구의 반응이 본문 없이 닫힌다`, `FR-015 탈퇴한 회원의 글과 댓글에 걸린 열린 신고와 재검토 요청이 닫힌다`, `US2-AC8 추천의 값이 지워진다`
- [X] T015 [US2] `post`의 `PostService.create`, `CommentService.write`가 저장 직후 `MemberApi.isWithdrawn`을 본다. `apps/api/src/test/kotlin/com/ogu/post/WithdrawalRaceTests.kt`: 탈퇴와 겹쳐 들어온 글과 댓글이 남지 않는다(Edge Cases, research R5)

## Phase 5: US3 다른 회원이 받은 것은 그대로다 (P1)

**Independent Test**: A가 B의 글에 공감하고 댓글을 달아 HP를 4 줄이고 레이드를 공격한 뒤 탈퇴해도 B의 숫자가 그대로다.

- [X] T016 [US3] `WithdrawnContentApiTests.kt`에 더한다: `US3-AC1 공감 수가 그대로다`, `US3-AC2 HP와 처치 여부가 그대로다`, `US3-AC3 레이드의 HP와 참여자 수가 그대로다`, `US3-AC4 다른 회원의 알림이 남고 행동한 회원이 null이다`, `US3-AC5 함께 물리쳐 준 회원의 수가 그대로다`, `US3-AC7 이미 받은 주간 리포트의 수치가 그대로다`, `US3-AC8 탈퇴한 회원이 낸 신고가 열린 채 남는다`. 구현이 필요하면 T009~T012에서 고친다

## Phase 6: US4 같은 계정으로 다시 가입한다 (P2)

**Independent Test**: 이메일과 소셜로 가입한 회원이 각각 탈퇴한 뒤 같은 계정으로 다시 가입해 온보딩부터 시작한다.

- [X] T017 [US4] `apps/api/src/test/kotlin/com/ogu/member/presentation/WithdrawalRejoinTests.kt`: `US4-AC1 같은 이메일로 가입된다`, `US4-AC2 같은 소셜 계정으로 새 회원이 된다`, `US4-AC3 쓰던 닉네임을 다시 쓸 수 있다`, `US4-AC4 새 회원의 활동과 알림과 도감과 리포트가 비어 있다`, `US4-AC5 예전에 공감한 글에 새 회원으로 다시 공감한다`. 구현이 필요하면 T006에서 고친다

## Phase 7: US5 정리가 중간에 멈춰도 끝까지 간다 (P2)

**Independent Test**: 정리의 한 부분이 실패하게 만든 채 탈퇴해도 탈퇴는 끝나고, 실패를 풀면 다음 차례에 남은 정리가 끝난다.

- [X] T018 [US5] `apps/api/src/test/kotlin/com/ogu/post/WithdrawalCleanupFailureTests.kt`, `WithdrawalRaceTests.kt`, `WithdrawnContentApiTests.kt`: `US5-AC1 정리가 실패해도 탈퇴는 끝나고 글은 보이지 않는다`, `US5-AC2 다시 보내면 남은 것만 한다`, `US5-AC4 같은 정리를 두 번 해도 댓글 수가 두 번 줄지 않는다`, `US5-AC5 탈퇴를 두 번 하면 두 번째는 401이다`, `US5-AC3 끝나지 않은 발행이 다시 나가면 정리가 이어진다`(재시작 때 다시 내보내는 것은 지금의 설정 `republish-outstanding-events-on-restart`이고 quickstart에서 손으로 본다), `SC-006 글 1,000개와 댓글 5,000개에서 탈퇴 요청의 쿼리 수가 고정이다`
- [X] T019 [US5] `member/application/WithdrawalCleanupQuery.kt`와 `presentation/WithdrawalOperatorController.kt`(`GET /api/v1/operator/withdrawals/cleanup`). `WithdrawalApiTests.kt`에 `US5-AC6 끝나지 않은 탈퇴의 수를 운영자가 본다`, 운영자가 아니면 404다

## Phase 8: 웹 (US1)

- [X] T020 [US1] `apps/web/src/app/api/auth/withdraw/route.ts`와 테스트: 204일 때만 쿠키를 지운다, 401이면 쿠키를 지우고 401, 그 밖의 실패는 쿠키를 두고 그대로 돌려준다(`US1-AC8`), origin-guard. `shared/server/route-guard.ts`에 `/withdraw`(로그인만 본다, `US1-AC12`)와 테스트
- [X] T021 [P] [US1] `apps/web/src/features/withdraw`: `useWithdrawMutation`, `WithdrawForm`. 단위 테스트: `US1-AC2 사라지는 것과 남는 것과 되돌릴 수 없다는 안내`, `US1-AC3 확인 칸을 채우기 전에는 누를 수 없다`, `US1-AC7 취소`, `US1-AC8 실패 안내`, `US1-AC11 운영자 안내`, `FR-021 확인 칸과 버튼이 이름으로 읽힌다`
- [X] T022 [US1] `apps/web/src/app/withdraw/page.tsx`(`US1-AC10 도움받을 곳`), `entities/support-resource`의 `useWithdrawalNoticeQuery`, `app/my/edit/page.tsx`(`US1-AC1`)와 `app/onboarding/page.tsx`(`US1-AC9`)의 들어가는 길, `app/(auth)/login/page.tsx`의 탈퇴 안내(`US1-AC4`)
- [X] T023 [US1] `apps/web/e2e-full/withdrawal.spec.ts`: 가입, 글쓰기, 탈퇴, 로그인 화면의 안내, 같은 계정으로 로그인이 되지 않음, 다른 회원에게 글이 보이지 않음, 같은 이메일로 다시 가입

## Phase 9: 마무리

- [X] T024 [P] 문서: `apps/api/AGENTS.md`(탈퇴 절), `docs/architecture/overview.md`, `docs/features.md`, `README.md`의 기능 표, `infra/RESTORE.md`(research R11), 002/004/005/008 스펙의 "탈퇴는 범위 밖" 문장에 013을 가리키는 말
- [ ] T025 `cd apps/api && ./gradlew clean build`, 웹의 `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`. 320px와 390px 화면을 눈으로 본다
- [ ] T026 별도 검토자가 브랜치 전체를 본다. 지적을 고친 뒤 `main`으로 PR을 연다. PR에 API 배포와 마이그레이션이 따로 필요하다고 적는다

## Dependencies

- T001, T002 → 전부. T003, T004 → T006, T009, T012, T015
- T005 → T006. T008 → T009 → T010. T011, T014, T016은 T010, T012, T013 뒤에 초록이 된다
- T006 → T017, T019, T020. T020 → T022 → T023. T021은 T001 뒤면 따로 할 수 있다
- T012, T013, T007은 서로 다른 모듈이라 함께 할 수 있다

## Implementation Strategy

MVP는 Phase 1~5와 Phase 8이다(P1 셋). 탈퇴가 되고, 글이 사라지고, 다른 회원의 숫자가 그대로다. Phase 6은 구현 없이 테스트만 더하는 묶음이 될 가능성이 크다(회원 행을 비우면 저절로 된다). Phase 7의 운영자 조회는 MVP 뒤에 붙여도 탈퇴 자체는 쓸 수 있다.
