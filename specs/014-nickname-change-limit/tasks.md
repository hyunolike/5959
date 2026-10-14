---

description: "Task list for 014-nickname-change-limit (닉네임 변경 제한)"
---

# Tasks: 닉네임 변경 제한

**Input**: Design documents from `/specs/014-nickname-change-limit/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 15개다(US1 9, US2 6).

## Phase 1: Setup

- [X] T001 `specs/014-nickname-change-limit/contracts/nickname-change-limit.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.15.0): `MemberProfile.nicknameChangeAvailableAt`(`type: [string, 'null']`, `format: date-time`), `updateMyProfile`의 409 설명에 `NICKNAME_CHANGE_LIMITED`. `pnpm --filter web gen:api`
- [X] T002 `apps/api/src/main/resources/db/migration/V14__nickname_change_limit.sql`: `member.nickname_locked_until TIMESTAMPTZ` NULL, `member_withdrawn_erased_check`에 `nickname_locked_until IS NULL`을 더해 다시 건다. 번호는 올리기 직전에 `origin/main`을 다시 본다

## Phase 2: US1 닉네임을 바꾸면 한동안 다시 바꿀 수 없다 (P1)

- [X] T003 [US1] `apps/api/src/test/kotlin/com/ogu/member/presentation/ProfileUpdateApiTests.kt`에 먼저 쓴다: `014 US1-AC1 처음 바꾸는 닉네임은 저장되고 프로필에 다시 바꿀 수 있는 때가 실린다`, `014 US1-AC2 제한 중의 닉네임 변경은 409이고 닉네임과 함께 보낸 직군도 그대로다`, `014 US1-AC3 US1-AC4 제한 중에도 직군과 경력, 지금과 같은 닉네임은 저장된다`, `014 US1-AC5 30일이 지나면 다시 바꾸고 그때부터 다시 잰다`, `014 US1-AC6 대소문자만 바꾼 것도 한 번이다`, `014 US1-AC7 다른 까닭으로 거절된 변경은 세지 않는다`, `014 US1-AC8 겹친 닉네임 변경은 하나만 받아들인다`, `014 US1-AC9 탈퇴하면 제한이 지워지고 다시 가입한 회원은 바로 바꾼다`, 온보딩은 제한을 걸지 않는다
- [X] T004 [US1] `shared`의 `ErrorCode.NICKNAME_CHANGE_LIMITED`(409), `member/infrastructure/config/NicknameChangeProperties.kt`(`ogu.member.nickname-change.interval`, 기본 30일), `Member`의 `nicknameLockedUntil`과 `updateProfile`, `withdraw`, `ProfileService`의 제한 확인(회원 행을 잠근 뒤, 중복 확인보다 먼저), `MemberProfileResponse.nicknameChangeAvailableAt`

## Phase 3: US2 바꾸기 전에 알고, 제한 중이면 언제 풀리는지 안다 (P1)

- [X] T005 [P] [US2] `apps/web/src/features/edit-profile/model/nickname-lock.ts`와 테스트: 제한 중인지(null, 지난 때, 앞으로의 때), 한국 시간의 날짜 문구
- [X] T006 [US2] `apps/web/src/features/edit-profile/ui/profile-form.tsx`와 테스트: `US2-AC1 바꾸기 전의 안내`, `US2-AC2 제한 중이면 칸이 잠기고 날짜가 보인다`, `US2-AC3 제한 중에도 직군과 경력은 저장된다`, `US2-AC4 409를 받으면 닉네임 칸에 안내가 보이고 내 정보를 다시 받는다`, `US2-AC5 잠긴 칸의 설명이 이름과 함께 읽힌다`
- [X] T007 [US2] `apps/web/e2e-full/mypage.spec.ts`: `014 US2-AC6 닉네임을 바꾼 뒤 다시 열면 칸이 잠겨 있고 직군은 바꿀 수 있다`. 닉네임을 두 번 바꾸는 기존 e2e가 있으면 고친다

## Phase 4: 마무리

- [X] T008 [P] 문서: `apps/api/AGENTS.md`(프로필 수정 문단), `README.md`의 기능 표, `docs/features.md`
- [X] T009 API의 `ktlintCheck`, `detekt`, `member`와 탈퇴 테스트, 계약 테스트. 웹의 `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`. e2e-full의 `mypage.spec.ts`를 실제 API로 돌린다
- [X] T010 `main`으로 PR을 연다. PR에 API 배포와 마이그레이션이 따로 필요하다고 적는다

## Dependencies

- T001, T002 → 전부. T003 → T004. T005 → T006 → T007. T004 → T007
