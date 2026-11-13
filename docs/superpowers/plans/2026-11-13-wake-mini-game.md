# 서버 대기 미니게임 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 서버 대기 중 선택해서 즐기는 25초 응원 방울 게임과 자연스러운 준비 완료 전환을 제공한다.

**Architecture:** auth-welcome 위젯 안에 게임을 두고 기존 ApiReadiness 상태를 입력으로 받는다. ApiWakeNotice가 시작과 닫기를 소유하고 회원 복구 경계에 놀이 여부를 전달한다. 서버 폴링과 인증 제출은 기존 구현을 유지한다.

**Tech Stack:** React 19, Next.js 16, 기존 Tailwind 토큰, Phosphor, Framer Motion LazyMotion, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-13-wake-mini-game-design.md`

## Global Constraints

- 한 판은 25초다. 선택해서 시작하며 서버 요청과 새 의존성을 추가하지 않는다.
- 준비 완료 전환은 최대 1.2초다. 기존 인증 자동 제출을 지연하지 않는다.
- 기존 디자인 시스템, 실제 캐릭터 자산, 48px 이상 버튼과 움직임 줄이기를 사용한다.

## Review Focus

- 준비 완료 직전 클릭으로 점수가 추가되거나 새 판이 시작되지 않는다.
- 탭 비활성화와 시간 이동 이후에도 판이 제때 종료된다.
- 연결 실패와 재시도 시 이전 판의 수집 타이머가 남지 않는다.
- 키보드 초점과 게임을 닫는 동작이 로그인 입력을 방해하지 않는다.
- 게임 완료가 기존 회원 화면 자동 복구와 인증 자동 제출을 막지 않는다.

### Task 1: 게임과 대기 안내 통합

**Files:** Create `apps/web/src/widgets/auth-welcome/model/wake-game.ts` and its test, and `ui/wake-mini-game.tsx` and its test. Modify `ui/api-wake-notice.tsx`; keep its existing tests as regression coverage.

**Interfaces:** `WakeMiniGame({ status, onClose })` consumes `ApiReadinessStatus`; `ApiWakeNotice({ context?, onPlayingChange? })` reports whether the game occupies the waiting surface.

- [x] 시작, 수집 중복 방지, 시간 종료, 다시 하기, 준비 완료 및 실패 시 중단 테스트를 먼저 작성하고 실패를 확인한다.
- [x] 기존 Button, Phosphor와 `m`을 이용해 여섯 방울과 오구 반응을 구현한다. 판은 실제 deadline으로 종료하고 cleanup에서 타이머를 해제한다.
- [x] 안내를 게임의 형제 상태 영역으로 두고, 경과 시간은 live 영역 밖에 둔다. 준비 완료 시 1.2초 후 닫거나 바로 계속할 수 있게 한다.
- [x] 게임 및 안내 테스트가 통과하는지 확인한다.

### Task 2: 회원 복구와 화면 검증

**Files:** Modify `apps/web/src/core/providers/authenticated-api-boundary.tsx`, `app-providers.api-recovery.test.tsx`, `apps/web/e2e/authenticated-api-recovery.spec.ts`, `apps/web/docs/DESIGN_SYSTEM.md`.

**Interfaces:** 경계는 `onPlayingChange`로 게임 중에만 완료 안내를 잠깐 유지하고 종료 시 기존 화면을 자동 복구한다.

- [x] 게임 중 준비 완료 후 자동 복귀, 게임 중 실패와 재연결 테스트를 먼저 작성하고 실패를 확인한다.
- [x] 회원 경계에 게임 표시 상태를 연결한다. 게임하지 않은 사용자의 복구 동작은 바로 진행한다.
- [x] 기존 인증 자동 제출, 초안 보존, 전체 웹 단위 테스트, 타입, 린트와 FSD 검사를 실행한다.
- [x] 모바일과 데스크톱의 브라우저 테스트 및 스크린샷으로 게임, 종료, 준비 완료, 실패를 확인한다.
- [x] 별도 코드 리뷰로 변경을 확인하고 필요한 수정을 검증한다.

## Execution Notes

- 사용자의 진행 지시에 따라 현재 세션에서 구현한다. 기존 작업 파일 두 개는 건드리지 않는다. 별도 worktree와 커밋은 만들지 않고 검토 가능한 diff로 남긴다.
- 코드 리뷰: Critical/Important 지적 없음. 인증 재시도 시 이전 판이 재개될 수 있다는 지적은 실패 시 놀이를 끝내는 요구에 맞춰 재현 테스트를 추가하고 수정했다(RED → GREEN).
- 전체 웹 단위 테스트: 172개 파일, 1,635개 테스트 통과. 타입 검사와 포맷 검사 통과. ESLint 오류 없음(기존 `query-client.ts` 경고 1건).
- FSD 검사: 파일 감시 한도를 피해 `CHOKIDAR_USEPOLLING=1 pnpm exec steiger ./src --watch=false`로 통과.
- 빌드: 현재 실행 환경이 Turbopack의 포트 사용을 차단해 `pnpm build --webpack`으로 프로덕션 빌드를 검증했다. 저장소의 빌드 설정은 바꾸지 않았다.
- 브라우저: 기존 Playwright 설정을 재사용하는 임시 설정으로 완성된 빌드를 3001 포트에 띄워 6개 시나리오 통과. 320px/1440px 놀이와 자동 복구, 390px 움직임 줄이기 및 로그인 입력 보존과 1회 자동 제출, 7분 실패와 재시도를 확인했다. 가상 시계는 비동기 응답을 고정 시각으로 가정하지 않고 상태 변화를 따라 진행한다.
- 화면 확인: 모바일 놀이, 로그인과 함께 열린 놀이, 데스크톱 완료 안내 스크린샷을 열어 캐릭터 이미지와 배치, 가로 넘침을 확인했다. 커밋과 배포는 하지 않았다.
