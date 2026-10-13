# 레이드 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 레이드 화면을 보스의 감정 색 무대, 큰 HP 숫자, 게임 버튼으로 다시 그리고, 홈의 보스 안내를 새 토큰으로 옮긴다.

**Architecture:** 무대(`MonsterStage`)가 `region` "보스"가 되고 그 안에 보스(`MonsterDisplay`의 `boss`), 참여자 수와 내 기여, 공격 버튼(`GameButton`)을 둔다. 보스의 HP 숫자는 `MonsterDisplay`가 막대 위에 36px로 그린다. 공격 버튼은 쿨다운 동안 눌린 채로 내려가 있다가 다시 올라온다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 5번)

이 계획은 쓴 세션이 곧바로 실행한다.

## Global Constraints

- PR은 `design/system-v2`로 보낸다. `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 실제 API e2e(`e2e-full/raid.spec.ts`)가 기대는 것을 바꾸지 않는다.
  - `region` "보스"의 글자에 "무기력"(감정 이름), "HP 30/30", "함께한 회원0명", "내 기여0"이 이어서 들어 있다. `dt`와 `dd` 사이에 다른 글자를 넣지 않는다.
  - `region` "레이드 결과"와 그 안의 제목, "걸린 시간", "다음 보스는".
  - 버튼 "공격하기"와 `aria-disabled`. 홈의 링크 이름 "레이드: {감정} 보스, HP n/m".
- 글 상세의 HP 글자는 지금처럼 전체 내용이 "HP n/m"이어야 한다(`detail` 변형은 바꾸지 않는다).
- 이 계획이 고치는 파일에는 옛 색 이름을 남기지 않고, 옮긴 뒤 목록을 갱신한다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 보스의 HP가 네 자리 이상이면 쉼표를 넣되("HP 4,120/10,000"), 세 자리 이하는 "HP 30/30" 그대로여야 한다. → Task 1
2. 쿨다운 동안 공격 버튼을 눌러도 요청이 가지 않아야 하고 초점은 버튼에 남아야 한다. 쉬는 중이면 회색으로 잠긴다. → Task 2
3. 보스가 없을 때, 불러오지 못했을 때, 끝난 보스의 결과 화면이 깨지지 않아야 한다. → Task 3
4. 쉬는 중 안내는 무대 안에 있어도 주의 색이어야 한다. → Task 3
5. 320px에서 큰 HP 숫자("HP 10,000/10,000")가 넘치지 않아야 한다. → Task 5

---

### Task 1: 보스의 큰 HP 숫자와 무대의 속성 전달

**Files:** `entities/monster/ui/monster-view.tsx`(+test), `entities/monster/ui/monster-stage.tsx`(+test)

- `MonsterDisplay`의 `boss` 변형: 감정 태그 줄 아래에 `<p>`를 막대 위에 둔다. "HP "는 작은 글자, 현재 HP는 `text-numeric font-extrabold`, "/최대"는 `text-body`. 숫자는 `toLocaleString("ko-KR")`. `detail` 변형은 그대로(막대 옆 "HP n/m", 쉼표 없음).
- `MonsterStage`가 `div`의 나머지 속성(`role`, `aria-label` 등)을 그대로 넘긴다.

- [ ] 브랜치 `design/raid`를 만들고 이 계획을 커밋한다.
- [ ] 실패하는 테스트: "DS-US3-AC3 보스의 HP 숫자는 36px 단계이고 네 자리부터 쉼표를 넣는다"(`HP 4,120/10,000`, 현재 HP 요소에 `text-numeric`), "세 자리 이하 보스의 HP 글자는 그대로다"(`HP 30/30`), "상세의 HP 글자에는 쉼표를 넣지 않는다", "무대는 role과 이름을 넘겨받는다".
- [ ] 구현, 커밋: `feat(web): 보스의 HP를 큰 숫자로 그린다`

### Task 2: 공격 버튼

**Files:** `features/raid-attack/ui/attack-button.tsx`(+test)

- `GameButton`으로 바꾼다. 쿨다운이나 요청 중이면 `aria-disabled`와 함께 눌린 모양(`translate-y-1 shadow-none`), 쉬는 중(`disabled` 속성)이면 회색(`border-line-strong bg-surface-sunken text-fg-subtle shadow-none`). `disabled` 속성은 쓰지 않아 초점이 남는다.
- 실패 문구는 `text-label text-danger`.

- [ ] 실패하는 테스트: "DS-US3-AC3 공격 버튼은 게임 버튼이다"(`bg-game`), "쿨다운 동안에는 눌린 채로 내려가 있다"(`translate-y-1`), "쉬는 중이면 회색으로 잠긴다"(`bg-surface-sunken`).
- [ ] 구현, 커밋: `feat(web): 공격 버튼을 게임 버튼으로 바꾼다`

### Task 3: 레이드 화면

**Files:** `widgets/raid-arena/ui/raid-arena.tsx`(+test), `app/raid/page.tsx`

- 전투: `<MonsterStage emotion role="region" aria-label="보스" className="rounded-surface flex flex-col gap-4 p-5">` 안에 안내 글, 보스, 수치(`dl`), 공격 버튼, 쉬는 중 안내(`text-warning`).
- 수치: 흰 면 타일 두 개(결과 화면은 세 개). `dt`는 `text-caption text-fg-subtle`, `dd`는 `text-title font-extrabold`. `dt` 바로 뒤에 `dd`.
- 결과: `<MonsterStage ... role="region" aria-label="레이드 결과">` 안에 제목(`text-title`), 보스, 수치, 다음 보스 안내, "피드로".
- 불러오는 중, 오류, 보스 없음: 지금 구조에 글자 토큰만.
- 페이지 머리말: 작은 글 `text-label text-fg-subtle font-bold`, 제목 `text-heading`.

- [ ] 실패하는 테스트: "DS-US3-AC3 전투 무대는 보스의 감정 색이다", "DS-US3-AC3 결과 무대도 보스의 감정 색이다", "DS-US3-AC4 쉬는 중 안내는 주의 색이다", "수치의 이름과 값은 사이에 다른 글자 없이 이어진다"(`함께한 회원2명`).
- [ ] 구현, 커밋: `feat(web): 레이드를 보스의 감정 색 무대로 다시 그린다`

### Task 4: 홈의 보스 안내

**Files:** `widgets/raid-arena/ui/boss-banner.tsx`(+기존 테스트)

- 링크에 `data-emotion`을 붙이고 `bg-emotion-tint rounded-control`로 그린다. 테두리는 없다. 글자는 `text-body-sm text-fg font-bold`와 `text-caption text-emotion-fg`. 이름(`aria-label`)은 그대로.

- [ ] 실패하는 테스트: "DS-US3-AC3 홈의 보스 안내는 보스의 감정 색이다".
- [ ] 구현, 커밋: `feat(web): 홈의 보스 안내를 감정 색으로 그린다`

### Task 5: 브라우저 검증과 PR

**Files:** `e2e/raid.spec.ts`(새로)

- [ ] 가짜 `/api/raid`(살아 있는 짜증 보스, HP 4120/10000, 그리고 HP 10000/10000)로 1440, 390, 320px에서 확인한다: 무대 배경이 짜증의 옅은 색(`rgb(253, 234, 234)`), HP 숫자의 글자 크기 36px, 공격 버튼의 배경 `rgb(255, 214, 10)`, 가로 넘침 없음. 캡처를 남긴다.
- [ ] 전체 e2e와 확인 명령, 빌드를 돌린다.
- [ ] `design/system-v2`로 PR을 연다. 제목: `feat(web): 레이드를 새 디자인 시스템으로 옮긴다`.
- [ ] 별도 검토자가 브랜치 전체를 본다.
