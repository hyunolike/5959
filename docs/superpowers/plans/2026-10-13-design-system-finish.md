# 디자인 시스템 개편 마무리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 비회원 홈을 몬스터 그림으로 다시 그리고, 남은 화면을 새 토큰으로 옮긴 뒤 옛 토큰과 Lucide를 지운다. 앞선 PR들의 검토에서 미뤄 둔 지적을 정리하고 문서를 맞춘다.

**Architecture:** 옮길 파일이 다 끝나면 `globals.css`의 옛 이름 묶음과 `legacy-style-files.json`을 지우고, 옛 색 이름 검사를 "하나도 없다"로 바꾼다. 게임 버튼은 정해진 파일에서만 가져올 수 있게 검사를 더한다. 움직임의 시간은 스펙의 세 단계(120, 200, 320ms)를 CSS 변수로 둔다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 8번)

이 계획은 쓴 세션이 곧바로 실행한다.

## Global Constraints

- PR은 `design/system-v2`로 보낸다. `apps/api`, `contracts/`는 건드리지 않는다. `lucide-react`를 지우는 것 말고 의존성 변화는 없다.
- 기능과 라우트는 그대로다. 비회원 홈은 보호된 API를 부르지 않고 가짜 글과 수치를 넣지 않는다(DS-US3-AC5). e2e가 기대는 제목 "마음 이야기", "마음 이야기는 로그인 후 볼 수 있어요", 버튼 "고민 쓰기", 로그인 안내의 흐름을 바꾸지 않는다.
- 이 PR이 끝나면 소스에 옛 색 이름, Tailwind 기본 색, Lucide가 하나도 없다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 비회원 홈이 회원의 글, 수치, 보호된 API 없이 몬스터 그림만으로 서비스를 보여 주어야 한다. → Task 1
2. 옛 토큰 묶음을 지운 뒤 어디에서도 색이 비지 않아야 한다(지워진 이름을 쓰는 클래스가 남으면 투명해진다). → Task 3
3. 게임 버튼이 글 상세의 공감과 레이드의 공격, 카탈로그 밖에서 쓰이면 검사가 막아야 한다. → Task 3
4. 계약에 없는 감정 값이 와도 감정 태그에 이름이 비지 않아야 한다. 상대 시각 함수에 빈 값이 와도 "방금"이 아니라 빈 칸이어야 한다. → Task 4
5. 홈 설치 안내의 닫기와 취소가 공용 버튼으로 바뀐 뒤에도 뒤로 가기와 초점 복귀가 그대로여야 한다(기존 e2e). → Task 2

---

### Task 1: 비회원 홈

**Files:** `widgets/guest-home/ui/guest-home.tsx`, 새 `guest-home.test.tsx`

- 맨 위: 제목과 한 줄 소개 → 다섯 감정의 몬스터 줄(48px 썸네일과 감정 이름, 감정 색 바탕) → 로그인 안내(제목, 설명, 버튼 둘, 가입 링크) → "오구오구는 이렇게 써요" 세 단계.
- "메뉴 안내" 목록은 지운다. 메뉴는 화면의 메뉴가 잠금 표시와 함께 이미 보여 준다.
- [ ] 브랜치 `design/finish`를 만들고 이 계획을 커밋한다.
- [ ] 실패하는 테스트: "DS-US3-AC5 다섯 감정의 몬스터 그림을 보인다", "DS-US3-AC5 가짜 글, 수치, 피드 구역이 없다", "GUEST-US1-AC2 버튼은 로그인 안내를 연다".
- [ ] 구현, 커밋: `feat(web): 비회원 홈을 몬스터 그림으로 다시 그린다`

### Task 2: 남은 화면

**Files:** `features/install-app/ui/home-install.tsx`, `app/page.tsx`, `app/debug/error-probe/page.tsx`, `widgets/service-status/ui/service-status-view.tsx`

- 대응표로 옮긴다. 직접 만든 버튼은 `Button`과 `IconButton`, 아이콘은 Phosphor. 서비스 상태의 점은 정상 `bg-fg`, 느림 `bg-warning`, 멈춤 `bg-danger`이고 상태는 글자로도 보인다(지금 그대로).
- [ ] 옮기고 기존 테스트와 e2e(`install-interactions`)가 그대로 통과하는지 본다. 커밋: `feat(web): 남은 화면을 새 토큰으로 옮긴다`

### Task 3: 옛 토큰과 Lucide를 지운다

**Files:** `core/styles/globals.css`, `core/styles/token-usage.test.ts`, `core/styles/legacy-style-files.json`(삭제), `apps/web/package.json`

- [ ] `token-usage.test.ts`를 "소스에 옛 색 이름이 하나도 없다"와 "DS-US3-AC2 게임 버튼은 정해진 파일에서만 쓴다", "Lucide를 가져오는 파일이 없다"로 바꾼다. 실패를 본다.
- [ ] `globals.css`의 옛 이름 묶음(`--color-background`부터 `--shadow-surface`까지)을 지우고 `legacy-style-files.json`을 지운다. `pnpm --filter web remove lucide-react`.
- [ ] `tokens.test.ts`에 "옛 토큰 이름이 globals.css에 없다"를 더한다. 빌드와 e2e 전체를 돌린다.
- [ ] 커밋: `refactor(web): 옛 토큰과 Lucide를 지운다`

### Task 4: 미뤄 둔 지적

앞선 PR들의 검토에서 사소하다고 미룬 것 가운데 화면에 영향이 있는 것을 고친다.

| 지적 | 고치는 법 |
| --- | --- |
| 버튼의 한국어 문구가 음절 중간에서 끊긴다 | `Button`, `GameButton`에 `break-keep` |
| `Textarea`를 가로로 끌 수 있다 | 기본을 `resize-y`로 |
| 계약에 없는 감정이면 감정 태그에 이름이 없다 | "감정"으로 대신한다 |
| 로딩 뼈대가 들여 있고 거의 안 보인다 | 좌우 여백을 없애고 막대를 `bg-line`으로 |
| 상대 시각 함수가 빈 값에 "방금"을 돌려준다 | 빈 값이면 빈 문자열 |
| "필터 지우기"가 칩보다 들어가 보인다 | `-ml-3` |
| 홈 배너의 HP에 쉼표가 없다 | 보이는 글자에 쉼표 |
| 레이드에 보스 이름이 큰 글자로 없다(`text-display`를 쓰는 화면이 없다) | 전투 무대 위에 "오늘의 보스"와 감정 이름을 `text-display`로 |
| `PostEditorCard` 이름이 낡았고 안내가 혼자 가운데 정렬이다 | `PostEditorScreen`으로, 안내는 왼쪽 정렬 |
| 로그인 소개 글의 크기가 글자 단계 밖이다 | `text-display` |
| 카탈로그의 선택 상자에 잠김과 오류 상태가 없다 | 더한다 |
| e2e의 글꼴 검사가 이름만 본다 | `document.fonts.check` |
| 움직임의 시간이 스펙의 세 단계와 다르다 | `--duration-fast/base/slow` 변수를 두고 `.ogu-pressable` 120ms, 선택 표시 120ms, 반응 아이콘 200ms |
| `.env.example`에 카탈로그 깃발이 없다 | 더한다 |

- [ ] 동작이 바뀌는 것(감정 태그, 상대 시각, 보스 이름, 버튼 `break-keep`)은 실패하는 테스트를 먼저 쓴다.
- [ ] 구현, 커밋: `fix(web): 검토에서 미뤄 둔 지적을 정리한다`

### Task 5: 문서

**Files:** `apps/web/docs/DESIGN_SYSTEM.md`

- [ ] "옮기는 중" 구역을 지우고, `Select`, `MonsterSummary`, 움직임의 세 단계, 게임 버튼을 쓰는 곳의 검사, 감정 색 검증 절차를 적는다. 문서의 색 값 테스트가 통과하는지 본다. 커밋: `docs(web): 디자인 시스템 문서를 마무리한다`

### Task 6: 점검과 PR

- [ ] 전체 e2e와 확인 명령, 빌드를 돌린다. 비회원 홈을 320, 390, 1440px로 캡처해 눈으로 본다.
- [ ] `design/system-v2`로 PR을 연다. 제목: `feat(web): 비회원 홈을 다시 그리고 디자인 시스템 개편을 마무리한다`.
- [ ] 별도 검토자가 브랜치 전체를 본다. 접근성 점검(WCAG 2.1 AA)을 따로 맡긴다.
- [ ] 통합 브랜치를 `main`으로 보내는 일은 사용자와 함께 정한다. 운영 배포가 걸려 있다.
