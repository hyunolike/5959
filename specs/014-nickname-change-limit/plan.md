# Implementation Plan: 닉네임 변경 제한

**Branch**: `014-nickname-change-limit` | **Date**: 2026-10-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/014-nickname-change-limit/spec.md`

## Summary

닉네임을 한 번 바꾸면 30일 동안 다시 바꿀 수 없다. 온보딩의 첫 닉네임과 직군, 경력은 세지 않는다. 프로필 수정 화면은 바꾸기 전에 알리고, 제한 중이면 칸을 잠그고 언제 풀리는지 보인다.

`member`에 "다시 바꿀 수 있게 되는 때" 하나를 적는다. 프로필 수정이 회원 행을 잠근 뒤에 그 값을 보고 거절하거나 새로 적는다. 프로필 응답이 그 값을 실어 화면이 저장 전에 안다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1 / Next.js 16, TanStack Query 5, react-hook-form. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. 마이그레이션 `V14__nickname_change_limit.sql`(`member.nickname_locked_until`, 탈퇴 제약 갱신)

**Testing**: JUnit 5, MockMvc, Testcontainers / Vitest, Playwright

**Target Platform**: VM의 docker compose(API, 손으로 배포), Cloud Run(웹, 병합하면 배포)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 프로필 수정의 쿼리 수는 지금과 같다. 회원 행을 이미 잠그고 읽는다

**Constraints**:
- 30일 안에 닉네임이 두 번 바뀌지 않는다. 겹친 요청도 마찬가지다(FR-007)
- 직군과 경력, 지금과 같은 닉네임은 제한에 걸리지 않는다(FR-005)
- 다른 회원의 제한은 어디에도 나가지 않는다(FR-008)
- 새 의존이 없다. `member` 안에서 끝난다

**Scale/Scope**: 인수 조건 15개(US1 9, US2 6). 계약은 `MemberProfile`의 필드 하나와 오류 코드 하나. API는 `member`의 엔티티, 서비스, 응답. 웹은 프로필 폼

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | `member` 안의 변경이다. 새 의존이 없다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/nickname-change-limit.openapi.yaml](contracts/nickname-change-limit.openapi.yaml)을 루트에 먼저 합친다(0.15.0) | 통과 |
| III. 인수 조건은 곧 테스트다 | 15개에 ID가 있다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 닉네임을 바꿔 가며 다른 사람인 척하는 길을 좁힌다. 제한을 걸기 전에 알린다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 부르지 않는다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 자원이 없다 | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

## Project Structure

```text
apps/api/src/main/resources/db/migration/V14__nickname_change_limit.sql
apps/api/src/main/kotlin/com/ogu/
├── member/domain/Member.kt                              # nicknameLockedUntil, updateProfile, withdraw
├── member/application/ProfileService.kt                 # 제한 확인, 끝나는 때 적기
├── member/infrastructure/config/NicknameChangeProperties.kt, AuthConfig.kt
├── member/presentation/dto/MemberProfileResponse.kt     # nicknameChangeAvailableAt
└── shared/error/ErrorCode.kt                            # NICKNAME_CHANGE_LIMITED
apps/api/src/test/kotlin/com/ogu/member/presentation/ProfileUpdateApiTests.kt

apps/web/src/
├── features/edit-profile/model/nickname-lock.ts(+ test) # 제한 중인지, 날짜 문구
└── features/edit-profile/ui/profile-form.tsx(+ test)    # 안내, 잠금, 409
apps/web/e2e-full/mypage.spec.ts
contracts/openapi.yaml                                    # 0.15.0
apps/api/AGENTS.md, README.md, docs/features.md
```

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, 웹 타입 다시 만들기, 마이그레이션 | 기반 |
| 2 | API: 엔티티, 서비스, 응답, 테스트 | US1 |
| 3 | 웹: 폼의 안내와 잠금, 409, 테스트, e2e | US2 |
| 4 | 문서, 검토, PR | 마무리 |

## Complexity Tracking

위반이 없다.
