# 오구오구 Web

[![Next.js](https://img.shields.io/badge/Next.js-16-black?logo=next.js)](https://nextjs.org)
[![TypeScript](https://img.shields.io/badge/TypeScript-strict-3178C6?logo=typescript&logoColor=white)](https://www.typescriptlang.org)
[![Architecture](https://img.shields.io/badge/architecture-Feature--Sliced%20Design-orange)](https://feature-sliced.design)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)

오구오구의 Next.js 16 (App Router) 프론트엔드다.
[Feature-Sliced Design](https://feature-sliced.design)으로 구성했고, 원래
[nextjs-fsd-template](https://github.com/hyunolike/nextjs-fsd-template)에서
가져와 도메인 코드를 걷어낸 뼈대 위에 기능을 붙여 나간다. 지금 있는 것은
서버 상태 조회 예시(`widgets/service-status`)와 공용 계층뿐이다.

## Quick start

모든 명령은 저장소 루트에서 `pnpm --filter web <script>`로 실행한다.

```bash
pnpm install
pnpm --filter web dev
```

[http://localhost:3000](http://localhost:3000)을 연다. 브라우저는 백엔드
API를 직접 부르지 않고 같은 출처의 `/api/*` 라우트 핸들러만 호출한다 —
[BFF 패턴](../../docs/adr/0002-bff-auth.md)이다. 라우트 핸들러는 서버
전용 환경 변수 `API_ORIGIN`으로 실제 백엔드에 요청을 전달한다.

## Features

- 🏗️ **Feature-Sliced Design**, enforced by tooling, not just convention —
  `pnpm lint:fsd` runs [`steiger`](https://github.com/feature-sliced/steiger),
  the official FSD architecture linter, catching wrong-direction imports and
  public-API sidesteps
- 🔐 **BFF 인증** ([ADR-0002](../../docs/adr/0002-bff-auth.md)): 브라우저는
  `/api/*` 라우트 핸들러만 호출하고, 핸들러가 서버 전용 `API_ORIGIN`으로
  요청을 전달한다
- 🔄 **Server state via TanStack Query**, **client state via Zustand** kept
  to the one thing that's genuinely global — not a dumping ground for
  server data
- 🧾 **Forms with React Hook Form + Zod**, typed end to end
- ✅ **Vitest + Testing Library** for unit/component tests, **Playwright**
  for end-to-end tests — test names start with the spec's acceptance
  criterion ID (`US1-AC1 ...`)
- 🪝 **Husky + lint-staged + commitlint** (Conventional Commits) and a
  GitHub Actions CI pipeline (typecheck, lint, `lint:fsd`, tests, build, e2e)

## Stack

| Concern              | Choice                                                  |
| -------------------- | ------------------------------------------------------- |
| Framework            | Next.js 16 (App Router, Turbopack, React 19)            |
| Language             | TypeScript 5, strict                                    |
| Styling              | Tailwind CSS 4                                          |
| Server state         | TanStack Query 5                                        |
| Client state         | Zustand 5                                               |
| Forms                | React Hook Form 7 + Zod 4                               |
| Env vars             | `@t3-oss/env-nextjs`                                    |
| Architecture lint    | `steiger` (official FSD linter)                         |
| Unit/component tests | Vitest + Testing Library                                |
| E2E tests            | Playwright                                              |
| Formatting           | Prettier + `prettier-plugin-tailwindcss`                |
| Git hooks            | Husky + lint-staged + commitlint (Conventional Commits) |
| Package manager      | pnpm                                                    |

## Folder structure

```
src/
├── app/        Next.js routes + API route handlers (thin composition only)
├── core/       Global providers (TanStack Query), global styles
├── widgets/    Composed UI blocks (currently: service-status)
├── features/   One user action per slice (added as domain work lands)
├── entities/   Domain nouns (added as domain work lands)
└── shared/     UI kit, API client (`fetchApiHealth`, query client,
                 `ApiError`), env config, generic utils — zero domain
                 knowledge
```

## Scripts

모든 스크립트는 루트에서 `pnpm --filter web <script>`로 실행한다.

| Script                    | What it does                              |
| ------------------------- | ----------------------------------------- |
| `dev`                     | Start the dev server                      |
| `build` / `start`         | Production build / serve                  |
| `typecheck`               | `tsc --noEmit`                            |
| `lint`                    | ESLint                                    |
| `lint:fsd`                | `steiger` — FSD layer/public-API rules    |
| `format` / `format:check` | Prettier                                  |
| `test` / `test:watch`     | Vitest                                    |
| `test:e2e`                | Playwright (builds + boots the app first) |

## Docs

- [`docs/ARCHITECTURE.md`](./docs/ARCHITECTURE.md) — layer rules, why `core/`
  stands in for FSD's own `app` layer, the server/client state split, the
  BFF auth flow, and which "recent trend" choices were made deliberately and
  why
- [`CONTRIBUTING.md`](./CONTRIBUTING.md) — how to add a new slice, the PR
  checklist, and the code-quality bar this repo holds itself to

## Acknowledgments

This app started from a template built by studying two references directly,
not just loosely inspired by them:

- [seungmanchoi/nextjs-fsd-agent-template](https://github.com/seungmanchoi/nextjs-fsd-agent-template)
  for the layer layout and tech-stack shape
- [toss/frontend-fundamentals](https://github.com/toss/frontend-fundamentals)
  for the code-quality bar (readability, predictability, cohesion, coupling)
  applied inside each slice

## License

[MIT](./LICENSE)
