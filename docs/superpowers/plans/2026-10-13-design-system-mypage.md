# 마이페이지, 통계, 주간 리포트, 알림 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 마이페이지(프로필, 감정 통계, 내 활동), 프로필 수정, 주간 리포트, 알림을 새 토큰과 공용 부품으로 옮기고, 차트의 감정 색을 색각 이상에서도 구분되게 고친다.

**Architecture:** 감정의 진한 색 다섯 가지를 차트 색 검증기(dataviz 스킬의 `validate_palette.js`)를 통과하는 값으로 다시 잡는다. 옅은 색과 글자 색은 그대로다. 차트는 `EMOTION_COLORS`를 계속 쓰고, 글자는 글자 토큰만 쓴다. 나머지 화면은 대응표로 옮기고 직접 만든 버튼과 선택 상자를 공용 부품으로 바꾼다.

**Tech Stack:** Next.js 16, React 19, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 7번)

이 계획은 쓴 세션이 곧바로 실행한다.

## Global Constraints

- PR은 `design/system-v2`로 보낸다. `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 기능과 라우트는 그대로다. 실제 API e2e(`mypage.spec.ts`, `weekly-report.spec.ts`, `notification.spec.ts`)가 기대는 이름(탭 이름, 목록 이름, 표의 caption "최근 8주 감정별 몬스터 수", `data-testid`, 링크 이름, "함께 물리친 보스N" 같은 이어진 글자)을 바꾸지 않는다.
- 차트: 감정의 색은 감정을 따라가고 순서(ANXIETY, LETHARGY, LONELINESS, SELF_DEPRECATION, IRRITATION)는 고정이다. 값은 언제나 이름과 숫자로 함께 보인다. 글자는 감정 색을 입지 않는다. 낭독용 표를 유지한다.
- 감정 색은 감정을 나타낼 때만 쓴다. 알림의 위기 안내에는 쓰지 않는다.
- 이 계획이 고치는 파일에는 옛 색 이름과 Lucide를 남기지 않고, 옮긴 뒤 목록을 갱신한다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 새 감정 색 다섯 가지가 인접한 쌍마다 일반 시야와 색각 이상에서 구분되어야 한다(검증기 통과). 글자 색과 옅은 색의 대비는 그대로 4.5:1 이상이어야 한다. → Task 1
2. 한 감정만 100%이거나 모든 주가 0인 통계에서 차트가 깨지지 않아야 한다. → Task 2
3. 프로필 수정의 선택 상자가 공용 부품으로 바뀐 뒤에도 지금 값이 미리 골라져 있어야 한다. → Task 4
4. 안 읽은 알림과 읽은 알림이 색만이 아니라 굵기나 표시로도 구분되어야 한다. → Task 5
5. 320px에서 통계의 네 수치와 주간 리포트의 수치가 넘치거나 단어 중간에 끊기지 않아야 한다. → Task 6

---

### Task 1: 감정 색을 차트에서도 구분되게 다시 잡는다

**Files:** `core/styles/globals.css`, `entities/monster/model/types.ts`, `docs/DESIGN_SYSTEM.md`, `e2e/design-catalog.spec.ts`, `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md`

| 감정 | 옛 진한 색 | 새 진한 색 |
| --- | --- | --- |
| 불안 | #8b5cf6 | #6d28d9 |
| 무기력 | #7187e0 | #7187e0 (그대로) |
| 외로움 | #35b6d3 | #45c6dd |
| 자기비하 | #8a9a3c | #a3b03c |
| 짜증 | #e5484d | #cf2f38 |

옛 값은 검증기에서 두 가지가 실패했다: 자기비하와 짜증이 적록 색각에서 ΔE 2.2, 불안과 무기력이 일반 시야에서 ΔE 11.2. 새 값은 색각 이상 14.5, 일반 시야 17.4로 통과한다(밝은 바탕과의 대비가 3:1에 못 미치는 색이 있어 이름과 숫자, 표를 함께 둔다는 조건이 붙는다).

- [ ] 브랜치 `design/mypage`를 만들고 이 계획을 커밋한다.
- [ ] 실패하는 테스트: `tokens.test.ts`에 "DS-US1-AC3 감정의 진한 색은 차트 검증을 통과한 값이다"(다섯 값을 고정). e2e의 기대 rgb를 새 값으로 바꾼다.
- [ ] `globals.css`, `EMOTION_COLORS`, 문서, 스펙의 표를 새 값으로 바꾼다. 커밋: `fix(web): 감정 색을 색각 이상에서도 구분되게 다시 잡는다`

### Task 2: 감정 통계

**Files:** `widgets/emotion-stats-panel/ui/emotion-stats-panel.tsx`, `distribution-bar.tsx`, `weekly-chart.tsx`(+tests)

- 수치 네 개는 `text-numeric font-extrabold`, 이름은 `text-caption text-fg-subtle`. 가장 많이 나타난 몬스터는 `MonsterThumb`와 `EmotionTag`.
- 분포 막대: 높이 12px, 조각 사이 2px, 양 끝만 둥글게. 범례의 점은 감정 색, 글자는 글자 토큰.
- 주별 막대: 조각 사이 2px, 위쪽 끝 4px 둥글게, 기준선 `border-line`. 낭독용 표는 그대로.
- [ ] 실패하는 테스트: "DS-US1-AC3 분포 막대와 범례는 감정마다 같은 색이다", "한 감정만 있어도 막대가 그려진다", "DS-US3-AC6 수치 네 개는 numeric 단계다".
- [ ] 구현, 커밋: `feat(web): 감정 통계를 새 토큰과 다시 잡은 감정 색으로 그린다`

### Task 3: 주간 리포트

**Files:** `widgets/weekly-report/ui/weekly-report.tsx`, `entities/weekly-report/ui/weekly-report-item.tsx`, `app/report/[weekStart]/page.tsx`(+tests)

- 대응표로 옮긴다. 수치는 `text-title font-extrabold`, 감정별 막대는 Task 2와 같은 규칙. 위기 주의 안내는 주의 색.
- [ ] 실패하는 테스트: "DS-US3-AC4 위기 주의 안내는 감정 색을 쓰지 않는다", "DS-US1-AC3 감정별 글 수의 색은 감정 색이다".
- [ ] 구현, 커밋: `feat(web): 주간 리포트를 새 토큰으로 옮긴다`

### Task 4: 마이페이지와 프로필 수정

**Files:** `app/my/page.tsx`, `widgets/my-activity/ui/my-activity.tsx`, `features/edit-profile/ui/profile-form.tsx`, `app/my/edit/page.tsx`(+tests)

- 프로필 수정의 선택 상자는 공용 `Select`, 제출은 `Button lg`, 오류가 난 칸은 `aria-invalid`와 `aria-describedby`.
- 내 활동의 탭은 지금 구조(`role="tab"`, 밑줄 표시)를 유지하고 토큰만 바꾼다.
- [ ] 실패하는 테스트: "DS-US2-AC2 프로필 수정의 선택 상자는 52px 공용 부품이고 지금 값이 골라져 있다".
- [ ] 구현, 커밋: `feat(web): 마이페이지와 프로필 수정을 새 토큰으로 옮긴다`

### Task 5: 알림

**Files:** `entities/notification/ui/notification-item.tsx`, `widgets/notification-list/ui/notification-list.tsx`, `app/notifications/page.tsx`(+tests)

- 목록은 구분선으로 나눈 줄 모양이다. 안 읽은 알림은 굵은 글씨와 먹색 점, 읽은 알림은 보통 굵기와 `text-fg-subtle`. 시각은 `formatRelativeTime`.
- [ ] 실패하는 테스트: "DS-US3-AC1 안 읽은 알림은 굵은 글씨와 점으로, 읽은 알림은 보통 글씨로 보인다", "알림 시각은 상대 시각이고 datetime은 원래 값이다".
- [ ] 구현, 커밋: `feat(web): 알림을 새 토큰과 상대 시각으로 옮긴다`

### Task 6: 브라우저 검증과 PR

- [ ] `navigation-states.spec.ts`의 320px 통계 화면에 더한다: 네 수치가 한 줄(높이 48px 미만)이고 가로 넘침이 없다. 분포 막대의 조각 색이 새 감정 색이다.
- [ ] 전체 e2e와 확인 명령, 빌드를 돌리고 캡처를 눈으로 본다.
- [ ] `design/system-v2`로 PR을 연다. 제목: `feat(web): 마이페이지와 통계, 알림을 새 디자인 시스템으로 옮긴다`.
- [ ] 별도 검토자가 브랜치 전체를 본다.
