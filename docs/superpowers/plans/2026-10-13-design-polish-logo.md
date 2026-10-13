# 화면 완성도 개편 4: 새 로고와 아이콘 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 오구 얼굴 그림과 보라색 선 글자를 새 로고 한 벌로 바꾼다. 머리말, 설치 아이콘, 파비콘, 공유 이미지가 같은 로고를 쓴다.

**Architecture:** 화면이 쓰는 SVG 둘(가로형, 심볼)은 `public/brand`에 둔다. 아이콘과 공유 이미지는 `scripts/brand`의 원본 SVG에서 `scripts/generate-brand-icons.mjs`가 PNG로 만든다. 공유 이미지는 Next의 파일 규칙(`src/app/opengraph-image.png`)으로 싣고, 주소가 절대 경로가 되게 루트 메타데이터에 `metadataBase`를 둔다.

**Tech Stack:** Next.js 16, Playwright(Chromium으로 SVG를 PNG로 그린다), Vitest 5

**Spec:** `docs/superpowers/specs/2026-10-13-design-polish-and-logo-design.md` ("로고")

이 계획은 쓴 세션이 곧바로 실행했다. 브랜치 `design/logo`는 3번(`design/polish-login`) 위에 쌓는다. 로고는 사용자가 고른 방향(얼굴)으로 codex가 그렸다.

## Global Constraints

- 로고는 잉크(#17171c) 한 색에 잎만 초록(#3fa96b)이다. 보라색을 쓰지 않는다. 글꼴 파일과 그림 파일에 기대지 않는다.
- 파비콘은 PNG로 둔다(`src/app/icon.svg`를 두지 않는다는 지금의 검사를 지킨다).
- 설치 아이콘의 이름은 판을 올린다(`ogu-logo-v2-*`).
- 확인 명령: `pnpm --filter web typecheck && pnpm --filter web lint && pnpm --filter web lint:fsd && pnpm --filter web format:check && pnpm --filter web test`

## Review Focus

1. 원형으로 잘리는 설치 아이콘에서 심볼이 잘리지 않아야 한다. → Task 1
2. 공유 이미지의 주소가 운영에서 `localhost`가 되면 미리보기가 뜨지 않는다. → Task 3
3. 옛 파일을 가리키는 곳이 남으면 그림이 깨진다. → Task 2
4. 16px 파비콘에서 얼굴로 읽혀야 한다. → Task 1

---

### Task 1: 원본과 PNG

**Files:** `scripts/brand/*.svg`, `scripts/generate-brand-icons.mjs`, `public/brand/ogu-logo*.svg`, `public/pwa/ogu-logo-v2-*.png`, `public/brand/ogu-logo-v2-apple-touch-180.png`, `src/app/icon.png`, `src/app/opengraph-image.png`

- [x] 실패하는 테스트: "LOGO-AC2 아이콘의 원본 SVG는 scripts/brand에 있고 만드는 스크립트가 읽는다", "LOGO-AC4 로고는 글꼴과 그림 파일에 기대지 않는 SVG다", "LOGO-AC5 공유 이미지는 1200×630 PNG다".
- [x] 스크립트를 다시 쓴다. 가장자리가 잘려도 되는 아이콘은 바탕과 다른 점이 지름 80% 원 밖에 있으면 만들지 않는다.

### Task 2: 쓰는 곳과 옛 파일

**Files:** `shared/ui/brand-mark.tsx`(+test), `app/manifest.ts`(+test), `app/layout.tsx`, `features/install-app/ui/home-install.tsx`, `app/brand-assets.test.ts`

- [x] 실패하는 테스트: "LOGO-AC1 로고는 심볼과 글자가 든 가로형 SVG 하나다", "LOGO-AC1 머리말에서 좁은 화면 28px, 넓은 화면 32px 높이로 놓이고 비율을 지킨다", "LOGO-AC3 옛 로고와 옛 아이콘 파일은 남기지 않는다", 설치 아이콘의 이름이 `ogu-logo-v2-`로 시작한다.
- [x] 쓰는 곳을 옮기고 옛 파일을 지운다.

### Task 3: 공유 이미지의 주소

**Files:** `app/layout.tsx`, `e2e/brand-icons.spec.ts`

- [x] 루트 메타데이터를 요청 때 만든다(`generateMetadata`). `metadataBase`는 `APP_ORIGIN`이다.
- [x] e2e: `og:image`가 절대 경로이고 그 주소가 1200px PNG로 열린다.

### Task 4: 문서와 확인

- [x] `apps/web/docs/DESIGN_SYSTEM.md`의 에셋 표, 스펙의 "한 벌".
- [x] 확인 명령과 `test:e2e`. 머리말, 아이콘, 공유 이미지를 눈으로 본다.
- [x] 별도 검토, PR.
