# 오구 인터랙션과 홈 추가 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** 기존 커뮤니티 기능에 실제 요청 상태와 접근성 있는 반응을 연결하고, 설치 또는 수동 홈 추가 안내를 제공한다.

**Architecture:** 기존 Framer Motion의 LazyMotion/domAnimation을 재사용한다. 설치 상태는 루트 provider에서 이벤트를 받고 홈 UI에서 소비한다. 서비스 워커와 개인정보 캐시는 추가하지 않는다. 대화상자는 기존 primitive의 포커스 동작을 유지하며 portal, 배경 inert, 스크롤 잠금, 선택적 뒤로 가기 닫기를 제공한다.

**Tech Stack:** Next.js 16.4, React 19, TanStack Query, Framer Motion 12(MIT), 기존 Button/ConfirmDialog/Lucide/Spoqa.

**Spec:** 현재 승인된 작업 메시지와 이 계획의 사용자 흐름. 구현은 이 세션에서 직접 수행한다.

## Global Constraints

- 기준 develop 59c2485, 작업 브랜치 feat/ogu-interactions-pwa. 원본과 이전 worktree를 보존한다.
- .github/deploy/infra 수정, 외부 업로드, 새 래스터 생성, push/PR/merge/배포 금지.
- 저장 API, 제목 필드, 개인화 피드, 가짜 활동은 추가하지 않는다.
- 실제 beforeinstallprompt 이벤트가 보관된 경우에만 사용자 클릭으로 prompt를 호출한다.
- 설치 성공은 appinstalled/standalone에서만 판단한다. userChoice accepted는 성공 확정이 아니다.
- iOS와 인앱 브라우저, 미지원 브라우저는 수동 안내. iOS26의 웹앱 열기 안내는 조건부 문장으로 제공한다.
- 기존 SVG의 표시 크기만 조정해 manifest 아이콘을 재사용하고 apple-touch-icon은 원본 오구 PNG를 연결한다. 실제 폰 표시와 설치는 별도 검증이 필요하다.

## Review Focus

- 같은 프레임의 중복 공감 클릭: 요청 한 번, 실패 시 원래 공감/HP 복원.
- 프롬프트 취소/거절/예외: 가짜 완료 없이 안내 상태와 재방문 정책 유지.
- storage 차단과 standalone/인앱 환경: 예외 없이 안내 분기, 설치 버튼 오표시 방지.
- 대화상자 반복 열기/닫기와 브라우저 뒤로 가기: 포커스 복귀, 배경 inert/스크롤 원복.
- 320px 및 reduced-motion 변경: 탭과 하단 메뉴/설치 안내가 겹치지 않고 모션을 제거.

### Task 1: 설치 상태와 manifest

**Files:** features/install-app/model/platform.ts, install-provider.tsx, ui/home-install.tsx, index.ts; src/app/manifest.ts, layout.tsx; core/providers/app-providers.tsx; public/pwa/*.svg.

**Interfaces:** platform classifier는 secure/standalone/userAgent/platform/maxTouchPoints를 받는다. provider는 canPrompt, pending, installed, environment, promotionVisible, message, dismissPromotion(), requestInstall()을 공급한다.

- [x] 환경 분류와 promotion 기간, 취소/수락/appinstalled/storage 차단 테스트를 먼저 작성하고 실패를 확인한다.
- [x] 프롬프트 이벤트를 루트에서 보관한다. promptRef는 실행 전에 소비하고 pendingRef로 중복 호출을 막는다.
- [x] 홈 안내 UI와 선택적 history-backed 안내 대화상자를 구현한다. 배너 닫기는 7일 보관하며 직접 여는 안내는 유지한다.
- [x] manifest id/scope/start_url/display와 공개 SVG/PNG 자산 응답을 검증한다. service worker 등록은 없다.

### Task 2: 공통 인터랙션과 기존 기능

**Files:** shared/lib/use-reduced-motion-preference.ts; shared/ui/interaction-provider.tsx, confirm-dialog.tsx, toast.tsx; features/like/ui/*; widgets/feed-list/ui/*; widgets/my-activity/ui/my-activity.tsx; app/home/page.tsx.

- [x] 뒤로 가기 대화상자 닫기와 키보드 탭 이동, 공감 pending/성공/실패 피드백 테스트를 먼저 작성한다.
- [x] LazyMotion/domAnimation과 m을 사용한다. CSS transforms는 클릭과 선택 상태에만 짧게 적용한다. reduced-motion은 duration 0으로 한다.
- [x] 공감의 기존 optimistic-cache를 보존한다. 즉시 latch, aria-busy, 성공 알림과 성공 시 아이콘 반응을 추가한다.
- [x] 정렬/활동 탭의 이동 표시와 로빙 키보드 포커스, 정적 로딩 스켈레톤과 실제 refetch 버튼을 추가한다.
- [x] 모달은 portal로 표시하고 열기/닫기 중 배경과 포커스를 관리한다. 기존 pending 확인 동작을 보존한다.

### Task 3: 검증과 로컬 커밋

- [x] 전체 단위 테스트, TypeScript, ESLint/FSD, Prettier, 생산 빌드를 실행한다.
- [x] 1440/390/320px 브라우저에서 설치 모의 이벤트, 취소/거절/중복/재방문/standalone/인앱, 공감 롤백과 재시도, 탭/모달 키보드와 뒤로 가기를 검증한다.
- [x] 공개 manifest와 아이콘, 서비스 워커 미등록, PNG 원본 해시, reduced-motion, JS 번들 크기를 확인한다.
- [x] 스크린샷은 로컬에 보존한다. 실제 폰 설치 미검증을 명시하고 로컬 커밋만 만든다.
