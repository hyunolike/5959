# Implementation Plan: 몬스터 도감

**Branch**: `010-monster-cards` | **Date**: 2026-10-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/010-monster-cards/spec.md`

## Summary

물리친 몬스터를 반짝이는 카드로 모아 보는 화면이다. 내 글의 몬스터가 처치되거나 다른 회원의 몬스터를 함께 물리치면 그 몬스터가 카드 한 장이 된다. 카드는 감정, 번호, 등급(세기로 정한다), 물리친 날, 내 몫을 보이고, 누르면 크게 보이며 포인터를 따라 기울고 빛난다.

카드는 저장하지 않는다. 몬스터와 공격의 기록에서 읽는다. API는 조회 하나(`GET /api/v1/members/me/monster-cards`)를 `feed`에 더하고, `feed`가 파사드 셋(`post` 둘, `monster` 하나)을 쿼리 세 개로 읽어 메모리에서 조립한다. 지운 글과 숨긴 글의 몬스터는 보이는 글만 고르는 `post`의 파사드에서 걸러진다. 응답에는 본문과 닉네임이 들어갈 자리가 없다.

웹은 도감 화면(`/cards`), 카드와 크게 보기, 메뉴 한 칸을 더한다. 좁은 화면의 메뉴가 다섯 칸이 되어 고민 쓰기가 한가운데에 놓인다. 결정 근거는 [research.md](research.md)에 있다.

## Technical Context

**Language/Version**: Kotlin 2.2, JDK 21 (API) / TypeScript 5 strict, Node 22 (웹)

**Primary Dependencies**: API는 Spring Boot 4.1, Spring Modulith 2.1. 웹은 Next.js 16, TanStack Query 5, Tailwind CSS v4, Phosphor. 새 라이브러리는 없다

**Storage**: PostgreSQL 17. 새 테이블은 없다. 실행 계획을 보고 필요하면 `monster_hp_log (member_id, monster_id)` 색인을 더한다

**Testing**: API는 JUnit 5, MockMvc, Testcontainers(실제 API로 글을 쓰고 처치한다). 웹은 Vitest, Playwright(`e2e/monster-cards.spec.ts` 가짜 API, `e2e-full/monster-cards.spec.ts` 실제 API)

**Target Platform**: VM의 docker compose(API), Cloud Run(웹)

**Project Type**: 웹 서비스(모노레포 `apps/api` + `apps/web`)

**Performance Goals**: 카드 100장을 가진 회원의 도감 첫 쪽이 1초 안에 보인다(SC-004). 조회의 쿼리는 카드 수와 상관없이 3개다

**Constraints**:
- 응답에 다른 회원의 글의 본문, 미리보기, 닉네임을 싣지 않는다(FR-007, SC-002)
- 지웠거나 숨긴 글의 몬스터는 카드가 되지 않는다(FR-002, constitution IV)
- 등급에 무작위가 없다. 글쓰기와 공감을 재촉하는 장치를 두지 않는다(FR-005, FR-016)
- 움직임 줄이기에서 움직이는 것이 없다(SC-005). 색은 `globals.css`에만 둔다
- `posts`와 `monsters`를 조인하지 않는다
- 월 인프라 비용 증가 0원. AI 호출이 없다

**Scale/Scope**: 인수 조건 30개(US1 9, US2 7, US3 6, US4 4, US5 4). 계약 연산 1개와 스키마 5개, 파사드 추가 1(`monster`)과 고침 1(`post`), 웹은 화면 1개, 위젯 1개, 카드 컴포넌트, 메뉴 한 칸

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 이 계획에서 | 판정 |
|---|---|---|
| I. 경계는 테스트로 강제한다 | 새 모듈이 없다. `feed`가 이미 의존하는 `post`와 `monster`의 파사드로만 읽는다(R2). 웹은 FSD 규칙대로 카드를 `entities/monster`에, 화면 조립을 `widgets`에 둔다 | 통과 |
| II. 계약이 코드보다 먼저다 | [contracts/monster-cards.openapi.yaml](contracts/monster-cards.openapi.yaml)에 연산과 스키마를 먼저 적었다 | 통과 |
| III. 인수 조건은 곧 테스트다 | 인수 조건 30개에 ID가 있다. 반짝임이 보기 좋은지는 자동으로 재지 못해 quickstart에 눈으로 보는 절차로 적었다 | 통과 |
| IV. 사용자 안전이 기능보다 먼저다 | 숨긴 글(위기 포함)의 몬스터는 카드가 되지 않는다. 등급은 뽑기가 아니고, 카드 수를 늘리라고 재촉하지 않는다(FR-016). 카드에 남의 글이 실리지 않는다 | 통과 |
| V. AI 장애가 핵심 흐름을 막지 않는다 | AI를 부르지 않는다 | 해당 없음 |
| VI. 무료 인프라 안에서 운영한다 | 새 컨테이너와 저장이 없다 | 통과 |

**Phase 1 설계 후 재확인**: 위반이 없다. Complexity Tracking은 비어 있다.

## Project Structure

### Documentation (this feature)

```text
specs/010-monster-cards/
├── spec.md, plan.md, research.md(R1~R10), data-model.md, quickstart.md
├── contracts/monster-cards.openapi.yaml
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
apps/api/src/main/kotlin/com/ogu/
├── monster/
│   ├── MonsterApi.kt                              # cardRows
│   ├── MonsterCardRow.kt
│   ├── application/MonsterQueryService.kt
│   └── domain/MonsterHpLogRepository.kt           # 카드 행을 읽는 쿼리 하나
├── post/
│   ├── PostSelectionApi.kt                        # visibleIds의 작성자 인자를 없어도 되게
│   └── application/PostSelectionReader.kt
└── feed/
    ├── application/MonsterCardQuery.kt, MonsterCardCursor.kt
    └── presentation/MyPageController.kt, dto/MonsterCardResponses.kt

apps/api/src/test/kotlin/com/ogu/feed/MonsterCardApiTests.kt

apps/web/src/
├── entities/monster/
│   ├── model/card.ts                              # 타입, 등급과 몫의 이름, 날짜
│   ├── api/use-monster-cards-query.ts             # 무한 쿼리
│   └── ui/monster-card.tsx                        # 카드 한 장(작게, 크게)
├── widgets/monster-collection/
│   └── ui/monster-collection.tsx, collection-summary.tsx, card-dialog.tsx, use-card-tilt.ts
├── widgets/app-shell/ui/app-shell.tsx             # 메뉴에 도감
├── app/cards/page.tsx
├── core/styles/globals.css                        # --holo-*, .ogu-card
└── shared/api/generated.ts, shared/config          # 쿼리 키

apps/web/e2e/monster-cards.spec.ts, apps/web/e2e-full/monster-cards.spec.ts
apps/web/docs/DESIGN_SYSTEM.md                     # 카드와 반짝임을 쓰는 곳
contracts/openapi.yaml                             # 0.10.0
```

**Structure Decision**: 모노레포의 두 앱을 그대로 쓴다. API는 새 모듈 없이 조회 하나를 더한다. 웹은 위젯 하나와 화면 하나를 더하고 카드는 몬스터 엔티티에 둔다(엔티티끼리는 서로 가져올 수 없다).

## 구현 순서

| 묶음 | 내용 | 스토리 |
|---|---|---|
| 1 | 계약 합치기(0.10.0)와 웹 타입 다시 만들기 | 기반 |
| 2 | API: `MonsterApi.cardRows`, `visibleIds` 고치기, `MonsterCardQuery`, 컨트롤러, 테스트 | US1, US2, US4 |
| 3 | 웹: 카드 타입과 조회, 카드 컴포넌트, 반짝임의 CSS | US2, US3 |
| 4 | 웹: 도감 화면(요약, 감정 고르기, 목록, 빈 도감), 크게 보기와 기울기 | US1, US3, US4 |
| 5 | 웹: 메뉴에 도감, e2e | US5 |
| 6 | 문서, 측정, 인수 조건 점검, 검토, PR | 마무리 |

## Complexity Tracking

위반이 없다.
