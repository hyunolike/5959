---

description: "Task list for 010-monster-cards (몬스터 도감)"
---

# Tasks: 몬스터 도감

**Input**: Design documents from `/specs/010-monster-cards/`

**Tests**: 포함한다. 테스트 이름은 스펙의 인수 조건 ID로 시작한다. 인수 조건은 30개다(US1 9, US2 7, US3 6, US4 4, US5 4). 테스트를 먼저 쓰고 실패를 본 뒤 구현한다.

**Organization**: plan의 구현 순서(묶음 1~6)를 따른다.

## Phase 1: Setup (묶음 1)

- [ ] T001 `specs/010-monster-cards/contracts/monster-cards.openapi.yaml`을 루트 계약 `contracts/openapi.yaml`에 합친다(0.10.0): 연산 `getMyMonsterCards`(`cursor`, `size` 1~50 기본 20, `emotion`), 스키마 `MonsterCardGrade`(`enum: [COMMON, RARE, LEGENDARY]`), `MonsterCardRole`(`enum: [MINE, TOGETHER]`), `MonsterCard`(`required: [cardNo, emotion, grade, role, defeatedAt, myDamage, helperCount, postId]`, `helperCount`는 null 허용이고 `minimum: 1`, `myDamage`는 `minimum: 0`), `MonsterCardSummary`, `MonsterCardPage`(`required: [items, nextCursor, summary]`). `pnpm --filter web gen:api`

## Phase 2: API US1, US2, US4 (묶음 2)

- [ ] T002 [US1] `apps/api/src/test/kotlin/com/ogu/feed/MonsterCardApiTests.kt`를 먼저 쓴다(MockMvc, 실제 API로 글을 쓰고 처치): `US1-AC1 내 글의 몬스터가 처치되면 MINE 카드가 한 장 있다`, `US1-AC2 함께 물리친 몬스터는 TOGETHER 카드다`, `US1-AC3 살아 있는 몬스터는 카드가 아니다`, `US1-AC4 처치된 뒤에만 반응한 회원에게는 카드가 없다`, `US1-AC5 최근에 물리친 것부터이고 커서로 이어서 본다`, `US1-AC6 카드가 없으면 빈 목록이고 요약은 모두 0이다`, `US1-AC7 지운 글과 숨긴 글의 몬스터는 카드가 아니고 숨김이 풀리면 돌아온다`, `US1-AC8 로그인하지 않으면 401이고 온보딩 전이면 403이다`, `US1-AC9 공감도 하고 댓글도 단 몬스터의 카드는 한 장이다`, `US2-AC2 세기 낮음, 보통, 높음은 COMMON, RARE, LEGENDARY다`, `US2-AC3 TOGETHER 카드는 내가 줄인 HP가 있고 helperCount가 null이다`, `US2-AC4 MINE 카드는 함께한 회원 수가 있고 myDamage가 0이다`, `US2-AC5 응답 원문에 다른 회원의 글의 본문과 닉네임이 없다`, `US2-AC7 다시 읽어도 카드 번호와 등급이 같다`, `US4-AC1 요약은 모은 수와 다섯 감정의 장수다`, `US4-AC3 감정으로 거르면 그 감정의 카드만 오고 요약은 그대로다`, size가 0이거나 51이거나 커서나 감정이 틀리면 400 `INVALID_REQUEST`, 쿼리는 3개다
- [ ] T003 [P] [US1] `monster`: `MonsterCardRow.kt`, `MonsterApi.cardRows(memberId, ownPostIds)`, `MonsterHpLogRepository`의 쿼리(처치된 몬스터 가운데 `post_id`가 내 글이거나 내 기록 가운데 `hp_after < hp_before`인 것이 있는 몬스터. 내가 줄인 HP의 합과 HP를 줄인 회원 수를 함께). `ownPostIds`가 비어도 돈다
- [ ] T004 [P] [US1] `post`: `PostSelectionApi.visibleIds(postIds, exceptAuthorId: Long? = null)`와 `PostSelectionReader`. 이미 있는 호출부(추천)는 그대로 돈다. 테스트: 작성자를 주지 않으면 내 글도 든다, 숨긴 글과 지운 글은 빠진다
- [ ] T005 [US1] `feed`: `application/MonsterCardQuery.kt`(파사드 셋으로 읽어 조립, 정렬 `defeatedAt DESC, cardNo DESC`, 감정 거르기, 요약), `application/MonsterCardCursor.kt`, `presentation/dto/MonsterCardResponses.kt`, `MyPageController.getMyMonsterCards`. `ContractTests`가 새 경로를 본다

## Phase 3: 카드 US2, US3 (묶음 3)

- [ ] T006 [P] [US2] `apps/web/src/entities/monster/model/card.ts`(타입, `CARD_GRADE_LABELS` 일반/희귀/전설, `CARD_ROLE_LABELS` 내 몬스터/함께 물리침, 물리친 날의 짧은 꼴과 긴 꼴)와 `api/use-monster-cards-query.ts`(무한 쿼리, 감정 거르기), 쿼리 키. 단위 테스트
- [ ] T007 [US2] `apps/web/src/entities/monster/ui/monster-card.tsx`와 `core/styles/globals.css`의 `--holo-*` 토큰과 `.ogu-card` 규칙. 단위 테스트: `US2-AC1 카드에 처치된 몬스터의 그림, 감정, 번호, 등급, 물리친 날이 있다`, `US2-AC2 등급은 글자로 적혀 있다`, `US2-AC3 함께 물리친 카드는 내가 준 피해를 보인다`, `US2-AC4 내 몬스터의 카드는 함께 물리친 회원 수를 보인다`, `US3-AC3 등급은 data-grade로 넘어가 반짝임을 가른다`, 그림을 낭독하지 않고 감정 이름을 글자로 읽는다, 감정 색은 `data-emotion`으로 준다. `tokens.test.ts`와 `token-usage.test.ts`가 통과한다

## Phase 4: 도감 화면 US1, US3, US4 (묶음 4)

- [ ] T008 [US4] `apps/web/src/widgets/monster-collection/ui/collection-summary.tsx`. 단위 테스트: `US4-AC1 모은 수와 다섯 감정의 장수를 보인다`, `US4-AC2 한 장도 없는 감정은 아직 만나지 못한 자리로 보인다`, `US4-AC3 감정을 누르면 눌린 상태가 되고 다시 누르면 풀린다`, `US4-AC4 감정의 이름과 숫자가 글자로 읽힌다`
- [ ] T009 [US3] `widgets/monster-collection/ui/card-dialog.tsx`와 `use-card-tilt.ts`. 단위 테스트: `US3-AC1 카드를 누르면 크게 보이고 내용이 모두 있다`, `US3-AC2 포인터를 움직이면 기울기 변수가 바뀐다`, `US3-AC4 움직임 줄이기에서는 기울기 변수가 바뀌지 않는다`, `US3-AC5 대화상자로 열리고 닫으면 초점이 열었던 카드로 돌아온다`, `US2-AC6 크게 본 카드에 글로 가는 링크가 있다`, Esc로 닫힌다
- [ ] T010 [US1] `widgets/monster-collection/ui/monster-collection.tsx`와 `apps/web/src/app/cards/page.tsx`(제목 "몬스터 도감"). 단위 테스트: `US1-AC5 카드가 더 있으면 더 보기가 있다`, `US1-AC6 카드가 없으면 얻는 방법과 글쓰기, 피드로 가는 길을 보인다`, 불러오는 중과 불러오지 못했을 때, `FR-016 순위와 재촉하는 문구가 없다`. `route-titles.test.ts`가 통과한다

## Phase 5: 메뉴와 e2e US5 (묶음 5)

- [ ] T011 [US5] `apps/web/src/widgets/app-shell/ui/app-shell.tsx`: 메뉴에 `/cards`(이름 "몬스터 도감", 짧은 이름 "도감")를 고민 쓰기와 기록 사이에 더하고 기록의 `wide`를 없앤다. 단위 테스트: `US5-AC1 좁은 화면의 메뉴는 이야기, 함께, 고민 쓰기, 도감, 기록이고 고민 쓰기가 셋째 칸이다`, `US5-AC2 넓은 화면의 메뉴에도 도감이 있다`, `US5-AC3 도감 화면에서는 도감이 지금 위치다`, `US5-AC4 익명에게 도감은 잠겨 있고 누르면 로그인 안내가 열린다`
- [ ] T012 [US5] `apps/web/e2e/monster-cards.spec.ts`(가짜 API): `US3-AC6`(320, 390, 1440px에서 넘침 없음), `US3-AC1`과 `US3-AC5`(열고 닫기, 초점), `US3-AC2`와 `US3-AC4`(기울기, 움직임 줄이기), `US5-AC1`(메뉴의 가운데). 기존 `navigation-states.spec.ts`가 통과한다
- [ ] T013 [US1] `apps/web/e2e-full/monster-cards.spec.ts`(실제 API): `US1-AC1`과 `US1-AC2`(처치한 뒤 두 회원의 도감), `US1-AC8`(로그인 전)

## Phase 6: 마무리 (묶음 6)

- [ ] T014 [P] 인수 조건 30개가 모두 테스트 이름이나 quickstart에 있는지 확인한다
- [ ] T015 [P] 문서: `apps/web/docs/DESIGN_SYSTEM.md`(카드와 반짝임, 쓰는 곳), `apps/api/AGENTS.md`(마이페이지 목록에 도감)
- [ ] T016 `cd apps/api && ./gradlew clean build`, 웹의 `typecheck`, `lint`, `lint:fsd`, `format:check`, `test`, `test:e2e`. 320과 390px의 화면을 눈으로 본다. SC-004를 재어 quickstart.md에 적는다
- [ ] T017 별도 검토자가 브랜치 전체를 본다. 지적을 고친 뒤 `main`으로 PR을 연다

## Dependencies

- T001 → T005, T006
- T002는 T003~T005 앞에 쓰고 실패를 본다. T003, T004 → T005
- T006 → T007 → T008, T009 → T010 → T011 → T012
- T005, T011 → T013

## Parallel

- T003(`monster`)과 T004(`post`)는 서로 다른 모듈이다
- 묶음 2(API)와 묶음 3~5(웹)는 계약만 합쳐지면 따로 나아간다

## Implementation Strategy

US1, US2, US5가 P1이다. 최소 범위는 카드가 보이는 도감과 메뉴(묶음 1, 2, T006~T007, T010, T011)다. 반짝임과 크게 보기(US3), 요약(US4)은 그 위에 얹는다. 한 PR로 나간다.
