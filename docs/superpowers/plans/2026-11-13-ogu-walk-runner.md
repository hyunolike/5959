# 오구의 마음 산책 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 기존 응원 방울을 한 버튼 산책 러너로 교체한다.
**Architecture:** 순수 TypeScript 고정 간격 물리 모델, Canvas 렌더러 및 RAF 컨트롤러, React 접근성 UI를 분리한다. 기존 ApiReadiness와 완료 전환을 재사용한다.
**Tech Stack:** React, Canvas 2D, requestAnimationFrame, 기존 디자인 시스템/Phosphor, 생성한 투명 스프라이트.
**Spec:** `docs/superpowers/specs/2026-11-13-ogu-walk-runner-design.md` (사용자가 진행 승인).

## Global Constraints

- 조작은 터치 또는 무대 초점에서 Space/↑ 한 번이다. 반복 키와 공중 점프를 무시한다.
- 첫 5초 연습, 15초마다 가속, 60초 이후 속도 유지. 최소 1.2초의 장애물 접근 시간을 보장한다.
- 숨김/초점 이탈 시 일시정지하며 명시적으로 재개한다. 원래 인증 제출과 1.2초 완료 전환을 유지한다.
- 새 의존성과 게임 서버 요청은 추가하지 않는다. 기존 미커밋 작업을 보존하고 커밋/배포하지 않는다.

## Review Focus

- 큰 RAF 간격과 탭 복귀가 즉사나 거리 증가를 만들지 않는다.
- 그림의 투명 여백과 다른 화면 폭이 충돌 판정을 바꾸지 않는다.
- 실패/재연결/준비 완료 중 게임 입력이 새 판을 시작하지 않는다.
- sessionStorage와 Canvas 지원 오류가 로그인과 회원 복구를 막지 않는다.
- 무대 이외의 키 입력과 초점 이동을 게임이 가로채지 않는다.

### Task 1: 러너 물리와 캐릭터 자산

**Files:** `apps/web/src/widgets/auth-welcome/model/wake-game.ts`, `.test.ts`, `public/brand/runner/*`.
**Interfaces:** `createRunner(): RunnerState`, `jumpRunner(state)`, `stepRunner(state, seconds)`, `runnerSpeed(elapsed)` 및 캐릭터/장애물 기하 상수.

- [x] 점프, 중복 점프 거절, 연습 구간, 충돌, 장애물 간격, 가속 한계, 프레임 분할 일관성 테스트를 작성하고 실패 확인.
- [x] 고정 1/120초 시뮬레이션과 화면 독립적인 논리 좌표를 구현하고 테스트 통과.
- [x] 기존 그림을 참조해 투명한 달리기/점프/쉬기 및 동료/장애물 스프라이트 생성, 프레임 기준점과 크기 검증.

### Task 2: Canvas 게임과 대기 UI 통합

**Files:** `ui/wake-mini-game.tsx`, `ui/runner-canvas.tsx`, `ui/runner-renderer.ts`, `ui/wake-mini-game.test.tsx`, `ui/api-wake-notice.tsx`.
**Interfaces:** `RunnerCanvas({ref, enabled, reducedMotion, onSnapshot, onLoad, onError})`, `RunnerHandle`의 act/pause/resume으로 제어. UI에는 100ms 이상 간격의 점수 스냅샷과 상태 전환만 전달.

- [x] 준비/실패/닫기/입력 보존/재시도 테스트와 일시정지/키 반복/저장 오류 테스트를 작성하고 실패 확인.
- [x] 초기 정지, 달리기, 충돌, 일시정지, 준비 완료 상태를 연결한다. 무대는 실제 버튼이며 터치/키보드 입력을 국소 처리한다.
- [x] 최종 상태와 판 종료만 안정된 live 영역으로 알리고 기존 완료/복구 테스트 통과.

### Task 3: 브라우저 검증과 문서

**Files:** `e2e/authenticated-api-recovery.spec.ts`, `core/providers/app-providers.api-recovery.test.tsx`, `docs/DESIGN_SYSTEM.md`, 설계 문서.

- [x] 320/1440px 터치 및 키보드 점프, 실제 충돌과 재시작, 초점/탭 일시정지, 준비 완료와 연결 실패, 390px 인증 입력 보존을 검증한다.
- [x] 전체 웹 단위 테스트, 타입/ESLint/FSD/포맷, 프로덕션 Webpack 빌드 실행.
- [x] 모바일/데스크톱 스크린샷을 열어 스프라이트와 실제 플레이를 확인하고 별도 코드 리뷰를 수행한다.

## Execution Notes

- 현재 체크아웃에서 승인한 설계를 구현한다. 앞선 미니게임 변경을 이어 수정하며 다른 사용자 파일은 건드리지 않는다.
- 물리: 120Hz 고정 간격. 초기 속도 90, 최대 130 논리 px/s. 점프 속도 440, 중력 1100. 320px 논리 폭을 비율대로 표시하며 장애물 생성 간격은 2.2~3.1초다. 80초 자동 플레이 테스트에서 착지와 연속 점프 가능성을 확인했다.
- 자산: 내장 image_gen으로 기존 오구와 불안 몬스터를 참조해 투명 6×4 atlas 제작. 위치와 프롬프트는 `docs/assets/ogu-runner-generation.md`.
- 코드 리뷰에서 인증/복구 차단 문제는 발견되지 않았다. 착지 중 충돌 시 동료의 점프 상태 정리, 저장소 차단 시 게임을 다시 열어도 방문 기록 유지 두 건은 실패 테스트로 재현 후 수정했다. 일시정지 버튼이 사라져도 무대에 키보드 초점이 남게 했다.
- 웹 전체 테스트 172개 파일, 1,646개 통과. 타입 검사와 포맷 검사 통과. ESLint 오류 0건(기존 query-client 경고 1건). FSD는 `CHOKIDAR_USEPOLLING=1 pnpm exec steiger ./src --watch=false`로 통과.
- `pnpm build --webpack`을 샌드박스 밖에서 실행해 최종 빌드 통과. 샌드박스 안에서는 종료 코드 0이어도 tracing 뒤 `middleware.js` 이름 변경 및 standalone 생성이 생략되어 빈 200 응답이 발생했다. 두 최종 산출물의 존재와 실제 페이지 응답을 함께 확인했다. 저장소의 Next.js 설정은 변경하지 않았다.
- 기존 Playwright 설정을 재사용하는 임시 설정으로 완성된 빌드에서 6개 시나리오 통과. 320/1440px 키보드 점프, 실제 장애물 통과, 충돌과 재시작, 일시정지와 자동 복귀; 390px 터치, 움직임 줄이기, 입력창의 키 입력 독립성과 로그인 1회 자동 제출; 7분 실패와 재연결을 확인했다.
- 모바일 점프/플레이/쉬기, 데스크톱 완료, 모바일 로그인과 일시정지 화면을 열어 확인했다. 점프 중 멈춘 캐릭터와 안내 문구가 겹치지 않게 문구를 오른쪽에 배치했다.
- 커밋과 배포는 하지 않았다. 로컬 미리보기는 3030 포트의 로그인 화면에서 확인한다.
