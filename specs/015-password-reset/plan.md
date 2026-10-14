# Implementation Plan: 비밀번호 재설정

**Branch**: `015-password-reset` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/015-password-reset/spec.md`

## Summary

이메일로 가입한 회원이 비밀번호를 잊으면 메일로 받은 링크에서 새 비밀번호를 정한다. 요청의 응답은 가입 여부와 상관없이 같고, 링크는 30분 동안 한 번만 쓰이며, 비밀번호를 바꾸면 모든 기기에서 로그아웃된다. 메일 발송은 꺼진 채로 나가고, 꺼져 있으면 들어가는 길이 보이지 않는다.

메일은 `shared`의 `MailSender` 포트 뒤에 SMTP로 둔다. 요청은 남용 확인과 회원 조회만 하고 이벤트를 내며, 커밋 뒤 리스너가 토큰을 만들어 해시로 저장하고 메일을 보낸다. 실패하면 발행 기록에 남아 다시 온다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1, **`spring-boot-starter-mail`(새로)** / Next.js 16, TanStack Query 5, react-hook-form

**Storage**: PostgreSQL 17. 마이그레이션 `V15__password_reset.sql`(`password_reset_token`). 남용 확인은 지금의 `login_attempt`를 쓴다

**Testing**: JUnit 5, MockMvc, Testcontainers / Vitest, Playwright

**Target Platform**: VM의 docker compose(API, 손으로 배포), Cloud Run(웹, 병합하면 배포)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 요청의 응답은 메일 발송을 기다리지 않는다. 로그인 화면이 켜져 있는지 묻는 조회는 60초 동안 다시 나가지 않는다

**Constraints**:
- 요청의 응답이 가입 여부를 드러내지 않는다(FR-002)
- 토큰, 이메일, 비밀번호의 원문이 이벤트, 발행 기록, 로그에 없다(FR-010)
- 메일 장애가 `/actuator/health`와 다른 기능에 번지지 않는다(FR-016)
- 꺼진 채로 나간다. 켜는 데 웹을 다시 내지 않는다(FR-012, FR-013)
- 새 모듈과 모듈 사이의 새 의존이 없다. `member`가 `shared`의 포트를 쓴다

**Scale/Scope**: 인수 조건 30개(US1 8, US2 10, US3 5, US4 7). 계약 연산 3개와 스키마 3개. API는 `shared/mail`, `member`의 재설정 서비스와 리스너와 컨트롤러. 웹은 화면 둘과 BFF 라우트 둘, 로그인 화면의 길과 안내. 인프라는 환경 변수의 통과

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | `member`가 `shared`의 `MailSender`를 쓴다(`shared`는 OPEN). 새 의존이 없다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/password-reset.openapi.yaml](contracts/password-reset.openapi.yaml)을 루트에 먼저 합친다(0.16.0) | 통과 |
| III. 인수 조건은 곧 테스트다 | 30개에 ID가 있다. 실제 메일이 받은편지함에 오는지는 켜는 사람이 quickstart로 본다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 가입 여부를 드러내지 않는다. 남의 이메일로 메일을 쏟아붓지 못하게 한도를 둔다. 비밀번호를 바꾸면 모든 세션을 끝낸다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 부르지 않는다. 같은 생각으로 메일 발송을 요청의 응답 뒤에 둔다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 꺼진 채로 나가 새 자원이 없다. 발송 서비스를 정할 때 비용이 들면 ADR을 쓴다(quickstart) | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

## Project Structure

```text
apps/api/build.gradle.kts                                   # spring-boot-starter-mail
apps/api/src/main/resources/db/migration/V15__password_reset.sql
apps/api/src/main/resources/application.yml, application-e2e.yml
apps/api/src/main/kotlin/com/ogu/
├── shared/mail/MailSender.kt, MailProperties.kt, MailConfig.kt(SMTP, 꺼짐, e2e)
├── shared/config/ProdMailSettingsCheck.kt
├── shared/error/ErrorCode.kt
└── member/
    ├── PasswordResetRequested.kt
    ├── domain/Member.kt(changePassword), PasswordResetTokenRepository.kt
    ├── application/PasswordPolicy.kt, PasswordResetService.kt, PasswordResetMailer.kt,
    │   PasswordResetThrottle.kt, ResetTokenGenerator.kt, PasswordResetTokenCleanupJob.kt
    ├── application/SignupService.kt, LoginThrottle.kt, WithdrawalService.kt   # 규칙 공유, 제한 풀기, 토큰 지우기
    ├── infrastructure/config/PasswordResetProperties.kt, security/SecurityPaths.kt
    └── presentation/PasswordResetController.kt, dto/
apps/api/src/test/kotlin/com/ogu/member/presentation/PasswordResetApiTests.kt, PasswordResetDisabledApiTests.kt

apps/web/src/
├── app/(auth)/forgot-password/page.tsx, reset-password/page.tsx
├── app/api/auth/password-reset/route.ts, confirm/route.ts(+ tests)
├── features/auth/password-reset/     # 요청 폼, 재설정 폼, 켜져 있는지 읽기
├── features/auth/email-login/        # 들어가는 길
└── app/(auth)/login/page.tsx         # 길을 그릴지, 바꿨다는 안내
apps/web/e2e-full/password-reset.spec.ts
contracts/openapi.yaml                 # 0.16.0
infra/compose.prod.yaml, infra/gcp/vm/compose.yaml     # 환경 변수의 통과
apps/api/AGENTS.md, README.md, docs/features.md
```

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, 웹 타입, 마이그레이션, 의존 | 기반 |
| 2 | `shared/mail`: 포트, SMTP, 꺼짐, e2e, 운영 검사 | US4 |
| 3 | `member`: 요청, 남용 확인, 리스너와 토큰, 재설정, 탈퇴와 정리 | US1, US2, US3 |
| 4 | 웹: BFF 라우트, 화면 둘, 로그인 화면, e2e | US1, US2, US4 |
| 5 | 인프라의 환경 변수, 문서, 검토, PR | 마무리 |

## Complexity Tracking

위반이 없다.
