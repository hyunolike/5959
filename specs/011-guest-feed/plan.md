# Implementation Plan: 비회원 둘러보기 피드

**Branch**: `011-guest-feed` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/011-guest-feed/spec.md`

## Summary

로그인하지 않은 방문자가 둘러보기로 들어오면 최근 고민 글 20개를 목록으로 본다. 닉네임 없이 직군과 경력만 보이고, 글을 누르면 로그인 안내가 뜬다. 지운 글, 숨긴 글, AI 분류까지 끝나 위험 없음으로 판정되지 않은 글은 내보내지 않는다.

API에는 로그인 없이 부르는 조회 하나(`GET /api/v1/public/feed`)를 `feed`에 더한다. 회원의 피드를 여는 것이 아니라 응답 타입을 따로 두어, 닉네임과 회원 번호가 들어갈 자리를 처음부터 없앤다. 넘겨 보기가 없어 얻을 수 있는 것은 그때의 최근 20개뿐이다. 웹은 비회원 홈에 목록을 그리고 문구를 바꾼 공개 범위에 맞춘다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: Spring Boot 4.1, Spring Modulith 2.1 / Next.js 16, TanStack Query 5. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. 새 테이블과 마이그레이션은 없다

**Testing**: JUnit 5, MockMvc, Testcontainers / Vitest, Playwright

**Target Platform**: VM의 docker compose(API, 손으로 배포), Cloud Run(웹, 병합하면 배포)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 조회의 쿼리는 4개로 고정이다

**Constraints**:
- 응답에 닉네임, 회원 번호, 댓글의 내용이 없다(FR-003)
- AI 분류까지 끝나 위험 없음으로 판정된 글만 내보낸다(FR-004, constitution IV)
- 넘겨 볼 수 없다. 한 번에 20개까지다(FR-001)
- 글 상세, 댓글, 회원의 피드는 로그인 없이는 지금처럼 401이다(FR-007)

**Scale/Scope**: 인수 조건 15개(US1 7, US2 6, US3 2). 계약 연산 1개와 스키마 2개, 파사드 추가 1(`safety`), 공개 경로 1개, 웹은 조회 하나와 카드 하나, 비회원 홈

## Constitution Check

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | `feed`가 `safety`의 파사드(`RiskApi`)를 쓴다. 순환이 없고 허용 표에 더한다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/guest-feed.openapi.yaml](contracts/guest-feed.openapi.yaml) | 통과 |
| III. 인수 조건은 곧 테스트다 | 15개에 ID가 있다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 누구나 보는 자리라 회원의 피드보다 좁게 잡는다: 우려 글과 판정 대기 글을 뺀다. 닉네임을 내보내지 않는다. 검색 엔진이 색인하지 않게 한다. 글쓴이에게 공개 범위를 알린다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 부르지 않는다. AI 분류가 늦으면 그 글이 목록에 늦게 나올 뿐이다 | 통과 |
| VI. 무료 인프라 안에서 운영한다 | 새 자원이 없다 | 통과 |

위반이 없다. Complexity Tracking은 비어 있다.

## Project Structure

```text
apps/api/src/main/kotlin/com/ogu/
├── feed/application/PublicFeedQuery.kt
├── feed/presentation/PublicFeedController.kt, dto/PublicFeedResponse.kt
├── safety/RiskApi.kt, application/RiskQueryService.kt, domain/RiskAssessmentRepository.kt   # clearedPostIds
└── member/infrastructure/security/SecurityPaths.kt                                          # 공개 경로
apps/api/src/main/java/com/ogu/feed/package-info.java                                        # safety
apps/api/src/test/kotlin/com/ogu/feed/presentation/PublicFeedApiTests.kt

apps/web/src/
├── entities/post/                 # PublicFeedItem, usePublicFeedQuery, PublicPostCard
├── widgets/guest-home/            # 목록, 빈 목록, 불러오기 실패, 문구
├── app/home/layout.tsx            # robots noindex
└── app/write/page.tsx             # 공개 범위 안내
apps/web/e2e/guest-home.spec.ts, apps/web/e2e-full/guest-security.spec.ts
contracts/openapi.yaml             # 0.12.0
```

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기, 웹 타입 다시 만들기 | 기반 |
| 2 | API: `RiskApi.clearedPostIds`, `PublicFeedQuery`, 컨트롤러, 공개 경로, 테스트 | US1, US2 |
| 3 | 웹: 조회와 카드, 비회원 홈, 문구, noindex, e2e | US1, US2, US3 |
| 4 | 문서, 검토, PR | 마무리 |

## Complexity Tracking

위반이 없다.
