---
description: "Task list for 017-anxiety-variants (여러 모습의 불안 몬스터)"
---

# Tasks: 여러 모습의 불안 몬스터

**Tests**: 포함한다. 테스트 이름은 인수 조건 ID로 시작한다.

## Phase 1: 그림

- [x] T001 Codex로 젤리, 구름, 시계 토끼의 멀쩡함 그림을 그리고 확인한다
- [x] T002 세 모습의 상처 입음, 약해짐, 쓰러짐 9장을 그리고 확인한다
- [x] T003 흰 배경을 지워 512×512 WebP로 바꿔 `apps/web/public/monsters/anxiety-v{2,3,4}-{stage}.webp`에 넣는다. 한 장 72KB 이하

## Phase 2: US1 글마다 다른 불안 몬스터 (P1)

- [x] T004 [US1] `entities/monster/model/sprite.test.ts`에 먼저 쓴다: `US1-AC1 글 번호의 나머지로 불안의 네 모습을 고른다`, `US1-AC4 다른 감정과 보스는 모습이 없다`, 경로 형식
- [x] T005 [US1] `entities/monster/model/sprite.ts`: `ANXIETY_LOOKS = 4`, `monsterLook(emotion, postId?)`, `spritePath(emotion, stage, boss, look)`. `index.ts`에서 내보낸다
- [x] T006 [US1] `entities/monster/ui`의 `MonsterSprite`, `MonsterThumb`, `MonsterSummary`, `MonsterDisplay`에 선택 프롭 `postId`. 테스트: `US1-AC3 같은 모습으로 단계가 바뀐다`, `US1-AC5 대체 텍스트는 그대로다`
- [x] T007 [US1] `entities/post/ui/post-card.tsx`, `public-post-card.tsx`의 `renderMonster(monster, postId)`. 위젯(`feed-list`, `guest-home`, `similar-posts`, `my-activity`)과 `post-detail`이 글 번호를 넘긴다. 테스트: `US1-AC2 피드와 상세가 같은 그림이다`

## Phase 3: US2 도감 (P2)

- [x] T008 [US2] `entities/monster/ui/monster-card.tsx`가 `card.postId`로 모습을 고른다. 테스트: `US2-AC1 도감 카드는 그 글의 모습으로 쓰러져 있다`

## Phase 4: 마무리

- [x] T009 `docs/adr/0006-illustrated-monsters.md`에 불안의 네 모습을 덧붙이고 README의 몬스터 그림 설명을 고친다
- [x] T010 typecheck, lint, lint:fsd, test, build, `test:e2e` 통과. 375px에서 피드의 네 모습을 눈으로 본다
- [ ] T011 PR(`feat(web): 불안 몬스터에 세 모습을 더해 글마다 다르게 나타난다 (017)`)
