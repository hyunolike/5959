---

description: "Task list for 015-password-reset (비밀번호 재설정)"
---

# Tasks: 비밀번호 재설정

**Input**: Design documents from `/specs/015-password-reset/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 30개다(US1 8, US2 10, US3 5, US4 7).

## Phase 1: Setup

- [ ] T001 `specs/015-password-reset/contracts/password-reset.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.16.0): `getPasswordResetAvailability`, `requestPasswordReset`(204, 400, 429, 503), `confirmPasswordReset`(204, 400), 스키마 `PasswordResetAvailability`(`required: [available]`), `PasswordResetRequest`(`required: [email]`), `PasswordResetConfirmRequest`(`required: [token, password]`). `pnpm --filter web gen:api`
- [ ] T002 `apps/api/src/main/resources/db/migration/V15__password_reset.sql`: `password_reset_token`(`member_id BIGINT` PK `REFERENCES member (id)`, `token_hash CHAR(64) NOT NULL UNIQUE`, `created_at`, `expires_at TIMESTAMPTZ NOT NULL`, `used_at TIMESTAMPTZ` NULL), 색인 `password_reset_token_expires_at_idx (expires_at)`. `apps/api/build.gradle.kts`에 `spring-boot-starter-mail`. 번호는 올리기 직전에 `origin/main`을 다시 본다

## Phase 2: Foundational (US4)

- [ ] T003 [US4] `shared/mail`: `MailSender`, `Mail`, `MailProperties`(`ogu.mail.enabled` 기본 false, `from`, `smtp.host/port/username/password/timeout`), SMTP 구현(`JavaMailSenderImpl`을 직접 만든다. Boot의 `spring.mail.*`를 쓰지 않는다), 꺼짐 구현, `e2e` 프로필의 가짜 구현. `shared/config/ProdMailSettingsCheck.kt`. `ErrorCode`에 `PASSWORD_RESET_LINK_INVALID`(400), `PASSWORD_RESET_THROTTLED`(429), `PASSWORD_RESET_UNAVAILABLE`(503). `application.yml`, `application-e2e.yml`

## Phase 3: US1, US3 재설정 메일을 요청한다, 남용을 막는다

- [ ] T004 [US1] `apps/api/src/test/kotlin/com/ogu/member/presentation/PasswordResetApiTests.kt`를 먼저 쓴다(메일을 켠 설정, 발송은 기록하는 가짜): `US1-AC2 이메일 가입 회원에게 링크가 든 메일이 하나 간다`, `US1-AC3 가입하지 않은 이메일도 204이고 메일은 가지 않는다`, `US1-AC4 소셜 회원과 탈퇴한 회원의 이메일에는 가지 않는다`, `US1-AC6 이메일의 모양이 아니면 400`, `US1-AC7 메일에 서비스 이름과 링크와 30분 안내가 있고 닉네임과 비밀번호가 없다`, `US1-AC8 대문자와 공백을 섞어도 같은 회원을 찾는다`, `US3-AC1 같은 이메일의 네 번째 요청은 204이고 메일이 가지 않는다`, `US3-AC2 같은 곳의 열한 번째 요청은 429와 Retry-After`, `US3-AC4 발행 기록과 토큰 테이블에 원문이 없다`
- [ ] T005 [US1] `member`: `PasswordResetRequested`, `PasswordResetThrottle`(`login_attempt`의 `reset-ip:`, `reset-email:` 키), `PasswordResetService.request`, `ResetTokenGenerator`(운영은 난수, `e2e`는 이메일에서), `PasswordResetTokenRepository`(덮어쓰기, 쓰기, 지우기), `PasswordResetMailer`(커밋 뒤, 회원을 다시 읽고 토큰을 만들어 보낸다. 한 시간이 지난 요청은 보내지 않는다), `PasswordResetController`의 `GET`과 `POST /api/v1/auth/password-reset`, `SecurityPaths.PUBLIC`

## Phase 4: US2 링크에서 새 비밀번호를 정한다

- [ ] T006 [US2] `PasswordResetApiTests.kt`에 먼저 쓴다: `US2-AC1 US2-AC2 새 비밀번호로 로그인되고 옛 비밀번호로는 안 된다`, `US2-AC3 한 번 쓴 링크는 400`, `US2-AC4 30분이 지난 링크는 400`, `US2-AC5 새로 요청하면 앞의 링크는 400`, `US2-AC6 규칙에 맞지 않는 비밀번호는 400이고 링크는 그대로다`, `US2-AC7 다른 기기의 토큰이 401`, `US2-AC8 로그인이 막혀 있던 회원도 바로 로그인한다`, `US2-AC9 지어낸 값은 같은 400`, `US2-AC10 같은 링크의 겹친 요청은 하나만 받는다`, `US3-AC5 탈퇴 전에 받은 링크는 400`
- [ ] T007 [US2] `member`: `PasswordPolicy`(가입과 함께 쓴다), `Member.changePassword`, `PasswordResetService.confirm`(규칙 → 토큰 쓰기 → 해시 바꿈 → 세션 지움, 커밋 뒤 로그인 제한 지움), `LoginThrottle.forgive`, `POST /api/v1/auth/password-reset/confirm`, `WithdrawalService`가 토큰을 지운다, `PasswordResetTokenCleanupJob`

## Phase 5: US4 메일 발송을 켜고 끈다

- [ ] T008 [US4] `PasswordResetDisabledApiTests.kt`(기본 설정): `US4-AC1 US4-AC2 꺼져 있으면 available이 false이고 요청은 503`. `PasswordResetApiTests.kt`에 `US4-AC3 켜져 있으면 available이 true`, `US4-AC4 발송이 실패하면 다시 보내고 그 링크를 쓸 수 있다`, `US4-AC5 발송이 실패해도 요청은 204`. `ProdMailSettingsCheckTest.kt`: `US4-AC6 켰는데 값이 빠지면 뜨지 않는다`. `US4-AC7`: 메일 헬스 지표가 없다

## Phase 6: 웹

- [ ] T009 [US1] `apps/web/src/app/api/auth/password-reset/route.ts`, `confirm/route.ts`와 테스트: origin-guard, 본문과 클라이언트 IP를 넘긴다, 상태와 오류 봉투를 그대로 돌려준다
- [ ] T010 [P] [US1] `apps/web/src/features/auth/password-reset`: 요청 폼(`US1-AC5 요청 뒤의 안내`, `US1-AC6`, `US3-AC2 남은 시간`, `US4-AC2`), 재설정 폼(`US2-AC1`, `US2-AC3 다시 요청하는 길`, `US2-AC6 두 칸이 다르면`), 켜져 있는지 읽기(서버). 단위 테스트
- [ ] T011 [US1] `apps/web/src/app/(auth)/forgot-password/page.tsx`, `reset-password/page.tsx`, `features/auth/email-login`의 들어가는 길(`US1-AC1`, `US4-AC1`), `app/(auth)/login/page.tsx`의 "비밀번호를 바꿨어요"
- [ ] T012 [US2] `apps/web/e2e-full/password-reset.spec.ts`: 가입 → 로그아웃 → 요청 → 정해진 링크로 새 비밀번호 → 새 것으로 로그인, 옛 것은 거절, 같은 링크를 다시 쓰면 거절

## Phase 7: 마무리

- [ ] T013 [P] `infra/compose.prod.yaml`, `infra/gcp/vm/compose.yaml`에 `MAIL_ENABLED`, `MAIL_FROM`, `MAIL_SMTP_*`, `OGU_WEB_ORIGIN`의 통과(기본은 꺼짐). `bash infra/tests/deploy_test.sh`, `infra/gcp/tests`
- [ ] T014 [P] 문서: `apps/api/AGENTS.md`(재설정과 `shared/mail`), `README.md`, `docs/features.md`, `apps/web/docs/ARCHITECTURE.md`, 002 스펙의 "범위 밖" 문장
- [ ] T015 API의 `ktlintCheck`, `detekt`, 재설정과 인증 테스트, 계약과 모듈 테스트. 웹의 `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`. e2e-full의 재설정과 로그인 스펙
- [ ] T016 별도 검토자가 브랜치 전체를 본다. 지적을 고친 뒤 `main`으로 PR을 연다

## Dependencies

- T001, T002 → 전부. T003 → T005, T007, T008
- T004 → T005. T006 → T007. T005 → T007
- T009 → T011. T010 → T011 → T012. T005, T007 → T012
