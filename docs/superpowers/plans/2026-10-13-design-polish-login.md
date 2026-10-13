# 화면 완성도 개편 3: 로그인 첫 화면 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 첫 화면이 입력란이 아니라 오구와 서비스 소개로 시작하게 한다.

**Architecture:** 페이지(`app/(auth)/login`)가 소개, 들어가는 길 셋, 아래 링크를 조립한다. 이메일 입력란은 `features/auth/email-login`의 `EmailLoginDisclosure`가 접었다 편다. 카카오 버튼의 색은 `features/auth/oauth-buttons`가 1번 계획에서 더한 `color-kakao`로 입힌다.

**Tech Stack:** Next.js 16, React 19, react-hook-form, Tailwind CSS v4, Vitest 5, Playwright

**Spec:** `docs/superpowers/specs/2026-10-13-design-polish-and-logo-design.md` ("로그인 첫 화면")

이 계획은 쓴 세션이 곧바로 실행했다. 브랜치 `design/polish-login`은 2번(`design/polish-screens`) 위에 쌓는다.

## Global Constraints

- 로그인의 동작(요청, 오류 문구, 이동)과 이름표("이메일", "비밀번호"), 버튼 이름("로그인", "카카오로 계속하기", "구글로 계속하기")을 바꾸지 않는다.
- 화면의 `h1`은 "로그인"이다. 실제 API e2e가 이것으로 화면을 알아본다.
- 회원가입 화면의 구성은 그대로다. 카카오 버튼의 색만 함께 바뀐다.
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 접힌 입력란이 보조기기에도 숨겨져야 하고(`hidden`), 펼치면 초점이 이메일 칸으로 가야 한다. → Task 2
2. "이메일로 로그인"과 "로그인" 버튼의 이름이 겹친다. 테스트는 `exact`로 고른다. → Task 4
3. 390×844에서 접힌 채로 들어가는 길 셋과 회원가입이 첫 화면 안에 있어야 한다(외부 로그인 오류 안내가 있을 때도). → Task 3
4. 카카오가 꺼진 배포(`KAKAO_LOGIN_ENABLED=false`)에서도 구성이 어색하지 않아야 한다. → Task 3

---

### Task 1: 카카오 버튼

**Files:** `features/auth/oauth-buttons/ui/oauth-buttons.tsx`(+test)

- [x] 실패하는 테스트: "POLISH-AC18 카카오 버튼은 카카오가 정한 노란 바탕이고 구글 버튼은 테두리 버튼이다".
- [x] 카카오는 `bg-kakao`와 잉크 글자, 구글은 `outline`.

### Task 2: 이메일 로그인 펼침

**Files:** `features/auth/email-login/ui/email-login-disclosure.tsx`(+test), `index.ts`

- [x] 실패하는 테스트: "POLISH-AC19 이메일 입력란은 접힌 채로 시작한다", "누르면 같은 화면에서 펼쳐지고 초점이 이메일 칸으로 간다", "다시 접어도 적어 둔 값이 남는다".
- [x] 보조 버튼(`aria-expanded`, `aria-controls`)과 `hidden`으로 접는 면. 폼은 접혀 있어도 그려 둔다.

### Task 3: 페이지

**Files:** `app/(auth)/login/page.tsx`(+test)

- [x] 실패하는 테스트: "POLISH-AC20 첫 화면은 오구와 서비스 소개로 시작하고 화면의 제목은 로그인이다", "카카오, 구글, 이메일 순으로 놓이고 가려던 곳을 넘긴다", "외부 계정 로그인이 실패해 돌아오면 버튼 위에서 알린다".
- [x] 오구(좁은 화면에서만. 넓은 화면은 왼쪽 소개에 있다), 머리 문구, 두 줄 소개, 감정 다섯, 들어가는 길 셋, 먼저 둘러보기와 회원가입.

### Task 4: e2e와 확인

**Files:** `e2e/design-system.spec.ts`, `e2e-full/support/login.ts`, 로그인 폼을 쓰는 실제 API e2e 넷

- [x] 로그인 폼을 쓰는 자리는 먼저 펼친다(`openEmailLogin`). "로그인" 버튼은 `exact`로 고른다.
- [x] 390px에서 접힌 화면이 첫 화면 안에 드는지, 카카오 버튼의 색을 e2e로 본다.
- [x] 확인 명령과 `test:e2e`를 돌리고 390px과 1440px을 눈으로 본다.
- [ ] 실제 API e2e와 별도 검토, PR.
