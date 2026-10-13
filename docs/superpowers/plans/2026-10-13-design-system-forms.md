# 글쓰기, 가입과 로그인, 온보딩 개편 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 입력 화면(글쓰기, 로그인, 가입, 온보딩, 글 고치기)을 새 토큰과 공용 입력 부품으로 옮긴다.

**Architecture:** 입력은 공용 `Input`, `Textarea`, 새 `Select`(52px)를 쓰고, 주요 버튼은 52px(`lg`) 먹색이다. 댓글 말투 선택은 공용 `Chip`이다. 로그인, 가입, 온보딩, 글쓰기의 테두리 카드를 걷어 내 좁은 화면에서 입력이 화면 폭을 다 쓰게 한다.

**Tech Stack:** Next.js 16, React 19, react-hook-form, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-system-overhaul-design.md` ("진행 순서" 6번)

이 계획은 쓴 세션이 곧바로 실행한다.

## Global Constraints

- PR은 `design/system-v2`로 보낸다. `apps/api`, `contracts/`는 건드리지 않는다. 새 의존성은 없다.
- 기능과 라우트, 폼의 검증 규칙은 그대로다. 실제 API e2e가 기대는 이름표("이메일", "비밀번호", "닉네임", "직군", "경력", "고민"), 버튼 이름("로그인", "가입하기", "완료", 말투 네 가지, 올리기 버튼), `select`의 `selectOption`, 오류 문구, `aria-pressed`를 바꾸지 않는다.
- 감정 색과 게임 요소는 이 화면들에 쓰지 않는다.
- 이 계획이 고치는 파일에는 옛 색 이름과 Lucide를 남기지 않고, 옮긴 뒤 목록을 갱신한다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. `select`가 공용 부품으로 바뀐 뒤에도 react-hook-form의 `register`(ref, onChange)와 기본값이 그대로 동작해야 한다. → Task 1, 3
2. 긴 말투 이름("따뜻한 조언해주기") 칩이 320px에서 넘치지 않아야 한다. → Task 5
3. 로그인 화면에서 구글 로그인과 회원가입 링크가 390×844 첫 화면 안에 보여야 한다(기존 e2e). → Task 5
4. 닉네임 중복 확인 안내가 초록색(`leaf`) 없이도 "쓸 수 있다"와 "쓸 수 없다"가 구분되어야 한다. → Task 3
5. 제출 중에 버튼이 잠기고 돌아가는 표시가 보여야 한다(글쓰기의 중복 제출 방지). → Task 2

---

### Task 1: 공용 Select

**Files:** `shared/ui/select.tsx`(+test), `shared/ui/index.ts`, `app/debug/design/page.tsx`

- `Select`: `<select>` 속성을 그대로 받고 `Input`과 같은 모양(52px, `border-line-strong`, `aria-invalid`면 `border-danger`)이다.
- [ ] 브랜치 `design/forms`를 만들고 이 계획을 커밋한다.
- [ ] 실패하는 테스트: "DS-US2-AC2 선택 상자는 52px이고 이름표와 이어진다", "고른 값이 바뀌면 onChange가 불린다".
- [ ] 구현하고 카탈로그의 입력 구역에 더한다. 커밋: `feat(web): 공용 선택 상자를 더한다`

### Task 2: 글쓰기

**Files:** `features/write-post/ui/post-form.tsx`, `write-form.test.tsx`, `widgets/post-editor/ui/post-editor.tsx`, `app/write/page.tsx`

- 본문은 공용 `Textarea`(`min-h-48`), 말투는 공용 `Chip` 네 개(`flex flex-wrap gap-x-2 gap-y-3`), 제출은 `Button lg`와 Phosphor `CircleNotch`.
- 글쓰기와 글 고치기의 테두리 카드를 걷어 낸다.
- [ ] 실패하는 테스트: 기존 "고른 말투는 테두리와 가라앉은 면으로…"를 "DS-US2-AC1 댓글 말투는 공용 칩이고 고른 것만 눌린 상태다"로 바꾼다(`h-9 rounded-full`, `aria-pressed`). "DS-US2-AC2 본문 입력은 공용 입력 모양이다"(`border-line-strong`).
- [ ] 구현, 커밋: `feat(web): 글쓰기를 공용 입력과 칩으로 옮긴다`

### Task 3: 온보딩

**Files:** `features/onboarding/ui/onboarding-form.tsx`(+test), `app/onboarding/page.tsx`

- 직군과 경력은 공용 `Select`, 완료는 `Button lg`. 닉네임 안내는 쓸 수 있으면 `text-fg font-medium`, 그 밖에는 `text-fg-subtle`. 오류는 `text-danger`.
- [ ] 실패하는 테스트: "DS-US2-AC2 직군과 경력은 52px 공용 선택 상자다", "쓸 수 있는 닉네임 안내는 초록색 없이 굵은 글씨다".
- [ ] 구현, 커밋: `feat(web): 온보딩을 공용 입력으로 옮긴다`

### Task 4: 로그인과 가입

**Files:** `app/(auth)/login/page.tsx`, `app/(auth)/signup/page.tsx`, `features/auth/email-login/ui/login-form.tsx`, `features/auth/email-signup/ui/signup-form.tsx`, `features/auth/oauth-buttons/ui/oauth-buttons.tsx`, `oauth-login-error-notice.tsx`, `widgets/auth-welcome/ui/auth-welcome.tsx`

- 계획 `2026-10-13-design-system-post-detail.md`의 대응표로 옮긴다. 제출 버튼은 `lg`. 테두리 카드를 걷어 낸다. 카카오 아이콘은 Phosphor `ChatCircle`(채움).
- [ ] 실패하는 테스트: "DS-US2-AC2 로그인과 가입의 제출 버튼은 52px이다"(각 폼 테스트).
- [ ] 구현, 커밋: `feat(web): 로그인과 가입을 새 토큰으로 옮긴다`

### Task 5: 브라우저 검증과 PR

- [ ] `e2e/design-system.spec.ts`의 로그인 테스트에 더한다: 이메일 입력의 높이 52px, 로그인 버튼의 배경 `rgb(23, 23, 28)`와 높이 52px. 글쓰기 화면(`navigation-states.spec.ts`의 320px)에서 말투 칩 네 개가 보이고 넘치지 않는지 확인한다.
- [ ] 전체 e2e와 확인 명령, 빌드를 돌리고 캡처를 눈으로 본다.
- [ ] `design/system-v2`로 PR을 연다. 제목: `feat(web): 글쓰기와 가입, 로그인을 새 디자인 시스템으로 옮긴다`.
- [ ] 별도 검토자가 브랜치 전체를 본다.
